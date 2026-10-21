package dev.otectus.mcacrime.usability;

import dev.otectus.mcacrime.McaCrimeConfig;
import dev.otectus.mcacrime.block.GuillotineBlock;
import dev.otectus.mcacrime.block.PilloryBlock;
import dev.otectus.mcacrime.block.entity.GuillotineBlockEntity;
import dev.otectus.mcacrime.captivity.CustodyOwner;
import dev.otectus.mcacrime.captivity.CustodyService;
import dev.otectus.mcacrime.compat.McaCompat;
import dev.otectus.mcacrime.crime.type.CrimeIds;
import dev.otectus.mcacrime.detention.DetentionRecord;
import dev.otectus.mcacrime.detention.DetentionService;
import dev.otectus.mcacrime.detention.ExecutionAuthorization;
import dev.otectus.mcacrime.facility.CrimeFacilityService;
import dev.otectus.mcacrime.facility.FacilityRole;
import dev.otectus.mcacrime.item.CrimeItems;
import dev.otectus.mcacrime.ledger.CapitalSentenceService;
import dev.otectus.mcacrime.ledger.CrimeRecord;
import dev.otectus.mcacrime.ledger.Resolution;
import dev.otectus.mcacrime.ledger.SentenceAssignmentService;
import dev.otectus.mcacrime.state.world.CrimeWorldData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.OptionalInt;
import java.util.Properties;
import java.util.UUID;

import static dev.otectus.mcacrime.usability.UsabilityRuntimeChecks.require;

/**
 * Workstream B on a real server (0.8.0): the device is assembled from its items, every interaction says
 * what happened, the blade strikes exactly once and only for the order it armed, and nothing -- a stale
 * fall, a pardon, a broken device, a restart -- leaves a lethal action behind.
 */
final class GuillotineChecks {

    private static final int Y = 100;
    private static final Path STATE = Path.of("usability-guillotine.properties");
    private static final UUID GUARD = UUID.nameUUIDFromBytes("usability:guard".getBytes());
    private final Fixtures f;
    private final UsabilityRuntimeChecks out;
    private final CrimeWorldData data;

    GuillotineChecks(Fixtures fixtures, UsabilityRuntimeChecks out) {
        this.f = fixtures;
        this.out = out;
        this.data = CrimeWorldData.get(fixtures.server);
    }

    // --- shared fixture steps ------------------------------------------------------------------------

    private ServerPlayer builder(String name, double x, double z, float yaw) {
        ServerPlayer player = f.player(name, x, Y, z);
        player.setYRot(yaw);
        player.setXRot(0.0F);
        return player;
    }

    private void use(ServerPlayer player, BlockPos pos, Direction face, ItemStack held) {
        player.setItemInHand(InteractionHand.MAIN_HAND, held);
        player.gameMode.useItemOn(player, f.level, player.getItemInHand(InteractionHand.MAIN_HAND),
                InteractionHand.MAIN_HAND, new BlockHitResult(Vec3.atCenterOf(pos).relative(face, 0.5D), face, pos, false));
    }

    /** Places a pillory by using the item on the floor, as a player would. Returns the lower half. */
    private BlockPos placePillory(ServerPlayer player, int x, int z) {
        BlockPos floor = new BlockPos(x, Y - 1, z);
        use(player, floor, Direction.UP, new ItemStack(CrimeItems.PILLORY.get()));
        return floor.above();
    }

    private void placeFrame(ServerPlayer player, BlockPos lower) {
        use(player, lower.above(), Direction.UP, new ItemStack(CrimeItems.GUILLOTINE.get()));
    }

    private LivingEntity subjectAt(BlockPos lower) {
        BlockState state = f.level.getBlockState(lower);
        Vec3 spot = PilloryBlock.standingPosition(state.getValue(PilloryBlock.FACING), lower);
        return f.mca(spot.x, spot.y, spot.z, false);
    }

    private void boards(ServerPlayer player, BlockPos lower) {
        use(player, lower.above(), Direction.NORTH, ItemStack.EMPTY);
    }

    private void frame(ServerPlayer player, BlockPos lower) {
        use(player, lower.above(2), Direction.UP, ItemStack.EMPTY);
    }

    private GuillotineBlockEntity blade(BlockPos lower) {
        return f.level.getBlockEntity(lower.above(2)) instanceof GuillotineBlockEntity entity ? entity : null;
    }

    private boolean detained(LivingEntity subject) {
        return DetentionService.forSubject(data, subject.getUUID()).isPresent();
    }

    /** Lawful custody, a guard-killing charge, a bound sentence, marked capital: what an arrest leaves. */
    private void condemn(LivingEntity subject) {
        UUID id = subject.getUUID();
        require(CustodyService.captureLawful(data, id, false, CustodyOwner.guard(GUARD), f.level.getGameTime(),
                BlockPos.ZERO, f.level.dimension().location()).ok(), "lawful custody");
        CrimeRecord killing = new CrimeRecord(UUID.randomUUID(), id, null, CrimeIds.KILL_GUARD, OptionalInt.empty(),
                true, f.level.getGameTime(), 60L, -80L, 0L, 1200L, Resolution.UNRESOLVED);
        data.addRecord(killing);
        UUID sentence = UUID.randomUUID();
        require(SentenceAssignmentService.assign(data, id, sentence, List.of(killing.id()), f.level.getGameTime()),
                "sentence binding");
        CapitalSentenceService.mark(data, id, sentence, false);
        require(CapitalSentenceService.condemned(f.server, id), "the subject is not condemned");
    }

    private void free(LivingEntity subject) {
        if (subject == null) {
            return;
        }
        DetentionService.releaseSubject(f.server, data, subject.getUUID(), DetentionService.ReleaseReason.OPENED);
        ExecutionAuthorization.clear(subject.getUUID(), ExecutionAuthorization.ClearReason.EXPIRED);
        if (data.getCustody(subject.getUUID()) != null) {
            CustodyService.release(f.server, subject.getUUID(), dev.otectus.mcacrime.captivity.CustodyReleaseReason.ADMIN);
        }
        Fixtures.discard(subject);
    }

    // --- the checks ----------------------------------------------------------------------------------

    void run() {
        if (UsabilityRuntimeChecks.phase() == 1) {
            restartVerify();
            return;
        }
        f.arena(64, 120, 12, 44, Y);
        assembly();
        harmlessAndCapture();
        leverExecution();
        staleOrder();
        pardonMidFall();
        destroyedDevice();
        longBlade();
        operatorExecute();
        guardExecution();
        out.atEnd(this::restartSetup);
    }

    private void assembly() {
        out.check("guillotine: pillory, refused frame, frame on top, both facings, from the items", () -> {
            ServerPlayer north = builder("BuilderNorth", 70.5, 22.5, 180.0F);
            ServerPlayer east = builder("BuilderEast", 76.5, 22.5, -90.0F);
            try {
                BlockPos a = placePillory(north, 70, 20);
                require(f.level.getBlockState(a).getBlock() instanceof PilloryBlock
                        && f.level.getBlockState(a.above()).getValue(PilloryBlock.HALF) == DoubleBlockHalf.UPPER,
                        "the pillory did not stand two blocks tall");
                use(north, a.above(), Direction.SOUTH, new ItemStack(CrimeItems.GUILLOTINE.get()));
                require(!(f.level.getBlockState(a.above().south()).getBlock() instanceof GuillotineBlock),
                        "a frame stood beside the pillory");
                require(f.wasTold(north, "mcacrime.device.guillotine.place_on_pillory"), "refusal said " + f.toldTo(north));
                placeFrame(north, a);
                BlockState frame = f.level.getBlockState(a.above(2));
                require(frame.getBlock() instanceof GuillotineBlock, "the frame did not cap the pillory: " + f.toldTo(north));
                require(frame.getValue(GuillotineBlock.FACING) == f.level.getBlockState(a).getValue(PilloryBlock.FACING),
                        "the frame did not take the pillory's facing");
                BlockPos b = placePillory(east, 78, 20);
                placeFrame(east, b);
                require(f.level.getBlockState(b.above(2)).getBlock() instanceof GuillotineBlock, "the east device");
                require(f.level.getBlockState(b).getValue(PilloryBlock.FACING)
                        != f.level.getBlockState(a).getValue(PilloryBlock.FACING), "both facings were not exercised");
                require(f.level.getBlockState(b.above(2)).getValue(GuillotineBlock.FACING)
                        == f.level.getBlockState(b).getValue(PilloryBlock.FACING), "the east frame's facing");
                f.forget(north);
                use(north, new BlockPos(84, Y - 1, 20), Direction.UP, new ItemStack(CrimeItems.GUILLOTINE.get()));
                require(f.wasTold(north, "mcacrime.device.guillotine.place_on_pillory"), "a bare frame said " + f.toldTo(north));
                f.set(90, Y + 1, 20, net.minecraft.world.level.block.Blocks.STONE.defaultBlockState());
                f.forget(north);
                use(north, new BlockPos(90, Y - 1, 20), Direction.UP, new ItemStack(CrimeItems.PILLORY.get()));
                require(!(f.level.getBlockState(new BlockPos(90, Y, 20)).getBlock() instanceof PilloryBlock),
                        "a pillory stood under a block");
                require(f.wasTold(north, "mcacrime.device.pillory.no_room"), "the obstructed pillory said " + f.toldTo(north));
                f.set(90, Y + 1, 20, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState());
                int top = f.level.getMaxBuildHeight() - 1;
                f.set(96, top - 1, 20, net.minecraft.world.level.block.Blocks.STONE.defaultBlockState());
                f.forget(north);
                use(north, new BlockPos(96, top - 1, 20), Direction.UP, new ItemStack(CrimeItems.PILLORY.get()));
                require(!(f.level.getBlockState(new BlockPos(96, top, 20)).getBlock() instanceof PilloryBlock),
                        "a pillory stood at the build limit");
                out.info("pillory at the build limit said " + f.toldTo(north));
            } finally {
                f.remove(north);
                f.remove(east);
            }
        });
    }

    private void harmlessAndCapture() {
        ServerPlayer warden = builder("Warden", 70.5, 30.5, 180.0F);
        ServerPlayer other = builder("OtherWarden", 71.5, 30.5, 180.0F);
        BlockPos lower = placePillory(warden, 70, 28);
        placeFrame(warden, lower);
        LivingEntity[] subject = new LivingEntity[1];
        out.check("guillotine: an empty pillory says where the subject must stand", () -> {
            f.forget(warden);
            boards(warden, lower);
            require(f.wasTold(warden, "mcacrime.device.pillory.empty"), "said " + f.toldTo(warden));
        });
        out.check("guillotine: the boards secure the villager at the spot and name them", () -> {
            subject[0] = subjectAt(lower);
            f.forget(warden);
            boards(warden, lower);
            require(detained(subject[0]), "nobody was secured: " + f.toldTo(warden));
            require(f.wasTold(warden, "mcacrime.device.guillotine.secured"), "said " + f.toldTo(warden));
            require(f.textTo(warden).contains(subject[0].getDisplayName().getString()), "the occupant was not named");
        });
        out.check("guillotine: a second player's click a moment later cannot undo the capture", () -> {
            boards(other, lower);
            require(detained(subject[0]), "the second click released the subject");
            require(f.wasTold(other, "mcacrime.device.pillory.busy"), "said " + f.toldTo(other));
        });
        out.check("guillotine: holding the frame on an occupied pillory never works the boards", () -> {
            f.forget(warden);
            use(warden, lower.above(), Direction.NORTH, new ItemStack(CrimeItems.GUILLOTINE.get()));
            require(detained(subject[0]), "trying to place a frame released the occupant");
        });
        out.check("guillotine: the blade falls harmlessly on an unsentenced villager and says why", () -> {
            f.forget(warden);
            frame(warden, lower);
            require(f.wasTold(warden, "mcacrime.device.guillotine.not_condemned.npc"), "said " + f.toldTo(warden));
            require(blade(lower) != null && blade(lower).bladeDown(), "the blade did not fall");
        });
        out.await("guillotine: the harmless fall completes and the villager stays held", 40, () -> {
            GuillotineBlockEntity entity = blade(lower);
            if (entity == null || !entity.completed()) return "blade still falling";
            if (!subject[0].isAlive()) return "the unsentenced villager died";
            if (!detained(subject[0])) return "the harmless fall released the occupant";
            return null;
        }, () -> {
            f.forget(warden);
            frame(warden, lower);
            if (!f.wasTold(warden, "mcacrime.device.guillotine.raised")) {
                out.results.add("FAIL guillotine: raising the blade says so: " + f.toldTo(warden));
            } else {
                out.results.add("PASS guillotine: raising the blade says so");
            }
            f.forget(warden);
            boards(warden, lower);
            boolean released = !detained(subject[0]);
            out.results.add((released && f.wasTold(warden, "mcacrime.device.pillory.released") ? "PASS" : "FAIL")
                    + " guillotine: the boards release the occupant and say the sentence stands: " + f.toldTo(warden));
            free(subject[0]);
            f.remove(warden);
            f.remove(other);
        });
    }

    private void leverExecution() {
        ServerPlayer executioner = builder("Executioner", 84.5, 30.5, 180.0F);
        BlockPos lower = placePillory(executioner, 84, 28);
        placeFrame(executioner, lower);
        LivingEntity condemned = subjectAt(lower);
        int[] hurtCount = {0};
        net.minecraftforge.common.MinecraftForge.EVENT_BUS.addListener(
                (net.minecraftforge.event.entity.living.LivingHurtEvent event) -> {
                    if (event.getEntity() == condemned) hurtCount[0]++;
                });
        out.check("guillotine: a condemned occupant's order is armed by the lever", () -> {
            boards(executioner, lower);
            require(detained(condemned), "not secured");
            condemn(condemned);
            f.forget(executioner);
            frame(executioner, lower);
            require(f.wasTold(executioner, "mcacrime.device.guillotine.armed"), "said " + f.toldTo(executioner));
        });
        out.await("guillotine: the blade strikes once and the condemned dies", 40, () -> {
            if (condemned.isAlive()) return "still alive";
            if (hurtCount[0] != 1) return "hurt " + hurtCount[0] + " times";
            return null;
        }, () -> {
            out.info("lever execution: custody after death=" + (data.getCustody(condemned.getUUID()) != null)
                    + ", detained=" + detained(condemned) + ", orders=" + ExecutionAuthorization.all().size());
            free(condemned);
            f.remove(executioner);
        });
    }

    private void staleOrder() {
        ServerPlayer executioner = builder("StaleExecutioner", 92.5, 30.5, 180.0F);
        BlockPos lower = placePillory(executioner, 92, 28);
        placeFrame(executioner, lower);
        LivingEntity condemned = subjectAt(lower);
        var bladeTicks = McaCrimeConfig.COMMON.guillotineActivationDelayTicks;
        int before = bladeTicks.get();
        out.check("guillotine: a fall whose order was cleared cannot borrow a newer order", () -> {
            boards(executioner, lower);
            condemn(condemned);
            bladeTicks.set(20);
            frame(executioner, lower);
            bladeTicks.set(before);
            require(ExecutionAuthorization.pending(condemned.getUUID(), f.level.getGameTime()).isPresent(), "not armed");
            // What a chunk unload does to the order, then a new order for the same subject at the same device.
            ExecutionAuthorization.clearAt(f.level.dimension().location(), lower, ExecutionAuthorization.ClearReason.CHUNK_UNLOADED);
            DetentionRecord occupancy = DetentionService.forSubject(data, condemned.getUUID()).orElseThrow();
            require(ExecutionAuthorization.arm(f.server, condemned.getUUID(), executioner.getUUID(), true, false,
                    f.level.dimension().location(), lower, f.level.getGameTime(), occupancy.occupantGeneration(),
                    occupancy.id()).isPresent(), "the newer order was not armed");
        });
        out.await("guillotine: the stale fall lands on nobody", 40, () -> {
            GuillotineBlockEntity entity = blade(lower);
            if (entity == null || !entity.completed()) return "blade still falling";
            if (!condemned.isAlive()) return "the stale fall executed the subject with a newer order";
            return null;
        }, () -> {
            free(condemned);
            f.remove(executioner);
        });
    }

    private void pardonMidFall() {
        ServerPlayer executioner = builder("PardonExecutioner", 100.5, 30.5, 180.0F);
        BlockPos lower = placePillory(executioner, 100, 28);
        placeFrame(executioner, lower);
        LivingEntity condemned = subjectAt(lower);
        var bladeTicks = McaCrimeConfig.COMMON.guillotineActivationDelayTicks;
        int before = bladeTicks.get();
        out.check("guillotine: a pardon during the fall is accepted", () -> {
            boards(executioner, lower);
            condemn(condemned);
            bladeTicks.set(20);
            frame(executioner, lower);
            bladeTicks.set(before);
            require(CapitalSentenceService.pardon(f.server, condemned.getUUID(), null)
                    == CapitalSentenceService.Clemency.GRANTED, "the pardon was refused");
        });
        out.await("guillotine: the pardoned subject survives and the executioner is told", 40, () -> {
            GuillotineBlockEntity entity = blade(lower);
            if (entity == null || !entity.completed()) return "blade still falling";
            if (!condemned.isAlive()) return "executed after a pardon";
            if (!f.wasTold(executioner, "mcacrime.device.guillotine.order_void")) return "said " + f.toldTo(executioner);
            return null;
        }, () -> {
            free(condemned);
            f.remove(executioner);
        });
    }

    private void destroyedDevice() {
        ServerPlayer executioner = builder("BreakerExecutioner", 108.5, 30.5, 180.0F);
        BlockPos lower = placePillory(executioner, 108, 28);
        placeFrame(executioner, lower);
        LivingEntity condemned = subjectAt(lower);
        out.check("guillotine: breaking the pillory releases the occupant once, clears the order, keeps the sentence", () -> {
            boards(executioner, lower);
            condemn(condemned);
            frame(executioner, lower);
            require(ExecutionAuthorization.pending(condemned.getUUID(), f.level.getGameTime()).isPresent(), "not armed");
            f.level.destroyBlock(lower, false);
            require(!(f.level.getBlockState(lower.above(2)).getBlock() instanceof GuillotineBlock), "the frame survived");
            require(!detained(condemned), "the occupant is still detained");
            require(ExecutionAuthorization.pending(condemned.getUUID(), f.level.getGameTime()).isEmpty(), "the order survived");
            require(CapitalSentenceService.condemned(f.server, condemned.getUUID()), "the sentence was erased");
            require(data.getCustody(condemned.getUUID()) != null && data.getCustody(condemned.getUUID()).isLawful(),
                    "lawful custody was lost");
            require(condemned.isAlive(), "the subject died");
        });
        free(condemned);
        f.remove(executioner);
    }

    private void longBlade() {
        ServerPlayer executioner = builder("SlowExecutioner", 70.5, 40.5, 180.0F);
        BlockPos lower = placePillory(executioner, 70, 38);
        placeFrame(executioner, lower);
        LivingEntity condemned = subjectAt(lower);
        var window = McaCrimeConfig.COMMON.executionDelayTicks;
        var bladeTicks = McaCrimeConfig.COMMON.guillotineActivationDelayTicks;
        int windowBefore = window.get();
        int bladeBefore = bladeTicks.get();
        out.check("guillotine: a blade longer than executionDelayTicks still strikes (window 2, blade 20)", () -> {
            boards(executioner, lower);
            condemn(condemned);
            window.set(2);
            bladeTicks.set(20);
            frame(executioner, lower);
            window.set(windowBefore);
            bladeTicks.set(bladeBefore);
            require(f.wasTold(executioner, "mcacrime.device.guillotine.armed"), "said " + f.toldTo(executioner));
        });
        out.await("guillotine: the long blade landed inside its order", 60, () -> condemned.isAlive() ? "still alive" : null,
                () -> {
                    free(condemned);
                    f.remove(executioner);
                });
    }

    private void operatorExecute() {
        LivingEntity condemned = f.mca(84.5, Y, 40.5, false);
        out.check("guillotine: /crime capital execute from the console names the missing guard, then the missing site", () -> {
            condemn(condemned);
            Fixtures.Console console = new Fixtures.Console();
            require(f.runConsole(console, "crime capital execute " + condemned.getStringUUID()) == 0, "it began");
            require(console.keys().contains("mcacrime.capital.escort.no_guard"), "no guard said " + console.keys());
            LivingEntity guard = f.mca(86.5, Y, 40.5, false);
            McaCompat.makeGuard(guard);
            try {
                Fixtures.Console second = new Fixtures.Console();
                require(f.runConsole(second, "crime capital execute " + condemned.getStringUUID()) == 0, "it began");
                require(second.keys().contains("mcacrime.capital.escort.no_site"), "no site said " + second.keys());
                require(condemned.isAlive() && CapitalSentenceService.condemned(f.server, condemned.getUUID()),
                        "the sentence was carried out or dropped without a site");
            } finally {
                Fixtures.discard(guard);
            }
        });
        free(condemned);
    }

    private void guardExecution() {
        ServerPlayer builder = builder("SiteBuilder", 100.5, 40.5, 180.0F);
        BlockPos lower = placePillory(builder, 100, 38);
        placeFrame(builder, lower);
        f.remove(builder);
        var window = McaCrimeConfig.COMMON.executionDelayTicks;
        int windowBefore = window.get();
        LivingEntity[] cast = new LivingEntity[2];
        out.check("guillotine: a guard-led execution begins from the console at an assigned site", () -> {
            require(CrimeFacilityService.assign(f.level, FacilityRole.EXECUTION_SITE, lower.east(3), "usability").isPresent(),
                    "the execution site was not assigned");
            LivingEntity condemned = f.mca(lower.getX() + 1.5, Y, lower.getZ() + 0.5, false);
            LivingEntity guard = f.mca(lower.getX() + 2.5, Y, lower.getZ() + 1.5, false);
            cast[0] = condemned;
            cast[1] = guard;
            McaCompat.makeGuard(guard);
            condemn(condemned);
            window.set(40);
            Fixtures.Console console = new Fixtures.Console();
            require(f.runConsole(console, "crime capital execute " + condemned.getStringUUID()) == 1,
                    "the escort did not begin: " + console.text());
        });
        out.await("guillotine: the guard places, arms, waits out the ceremony and the blade strikes once", 400,
                () -> cast[0] == null ? "no fixture" : cast[0].isAlive() ? "still alive" : null,
                () -> {
                    window.set(windowBefore);
                    free(cast[0]);
                    Fixtures.discard(cast[1]);
                });
    }

    private void restartSetup() {
        ServerPlayer executioner = builder("RestartExecutioner", 116.5, 30.5, 180.0F);
        BlockPos lower = placePillory(executioner, 116, 28);
        placeFrame(executioner, lower);
        LivingEntity condemned = subjectAt(lower);
        var bladeTicks = McaCrimeConfig.COMMON.guillotineActivationDelayTicks;
        int before = bladeTicks.get();
        out.check("guillotine: restart setup arms an order and leaves the blade falling across the restart", () -> {
            boards(executioner, lower);
            condemn(condemned);
            bladeTicks.set(200);
            frame(executioner, lower);
            bladeTicks.set(before);
            require(f.wasTold(executioner, "mcacrime.device.guillotine.armed"), "said " + f.toldTo(executioner));
            GuillotineBlockEntity falling = blade(lower);
            require(falling != null && falling.chopDelay() > 150, "the blade is not set to fall across the restart");
            Properties state = new Properties();
            state.setProperty("subject", condemned.getStringUUID());
            state.setProperty("device", lower.getX() + "," + lower.getY() + "," + lower.getZ());
            try (var writer = Files.newBufferedWriter(STATE)) {
                state.store(writer, "guillotine restart fixture");
            }
        });
        f.remove(executioner);
    }

    private void restartVerify() {
        Properties state = new Properties();
        try (var reader = Files.newBufferedReader(STATE)) {
            state.load(reader);
        } catch (Exception missing) {
            out.results.add("FAIL guillotine restart: no state from phase 0: " + missing);
            return;
        }
        UUID subject = UUID.fromString(state.getProperty("subject"));
        String[] xyz = state.getProperty("device").split(",");
        BlockPos lower = new BlockPos(Integer.parseInt(xyz[0]), Integer.parseInt(xyz[1]), Integer.parseInt(xyz[2]));
        out.check("guillotine restart: the old order is gone and the blade is still persisted mid-fall", () -> {
            require(ExecutionAuthorization.all().isEmpty(), "an order survived the restart");
            GuillotineBlockEntity entity = blade(lower);
            require(entity != null, "the device is gone");
            out.info("restart: chopDelay after load=" + entity.chopDelay() + " bladeDown=" + entity.bladeDown());
        });
        out.await("guillotine restart: the persisted fall lands on nobody; custody, sentence and occupancy stand", 260, () -> {
            GuillotineBlockEntity entity = blade(lower);
            if (entity == null) return "the device is gone";
            if (!entity.completed() || entity.chopDelay() > 0) return "blade still falling (" + entity.chopDelay() + ")";
            var subjectEntity = f.level.getEntity(subject);
            if (subjectEntity == null) return "the subject did not load";
            if (!subjectEntity.isAlive()) return "the restart resumed the execution";
            if (DetentionService.forSubject(data, subject).isEmpty()) return "occupancy was lost";
            if (!CapitalSentenceService.condemned(f.server, subject)) return "the sentence was lost";
            return null;
        }, null);
    }
}
