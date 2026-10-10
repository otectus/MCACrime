package dev.otectus.mcacrime.runtime;

import com.mojang.authlib.GameProfile;
import dev.otectus.mcacrime.McaCrimeConfig;
import dev.otectus.mcacrime.api.model.CrimeCommunityKey;
import dev.otectus.mcacrime.block.CrimeBlocks;
import dev.otectus.mcacrime.compat.CapitalsBridge;
import dev.otectus.mcacrime.compat.McaCompat;
import dev.otectus.mcacrime.decree.Decree;
import dev.otectus.mcacrime.decree.DecreeProvenance;
import dev.otectus.mcacrime.decree.DecreeReason;
import dev.otectus.mcacrime.decree.DecreeRefusal;
import dev.otectus.mcacrime.decree.DecreeMenuService;
import dev.otectus.mcacrime.decree.DecreeService;
import dev.otectus.mcacrime.decree.DecreeState;
import dev.otectus.mcacrime.engine.CrimeState;
import dev.otectus.mcacrime.enforcement.ArrestService;
import dev.otectus.mcacrime.enforcement.ArrestStates;
import dev.otectus.mcacrime.enforcement.GuardChallenge;
import dev.otectus.mcacrime.enforcement.GuardChallengeService;
import dev.otectus.mcacrime.jail.JailAnchor;
import dev.otectus.mcacrime.jail.JailRegistry;
import dev.otectus.mcacrime.jail.JailService;
import dev.otectus.mcacrime.jail.ReleaseReason;
import dev.otectus.mcacrime.justice.JusticeService;
import dev.otectus.mcacrime.justice.LegalDecision;
import dev.otectus.mcacrime.state.CrimeCapabilities;
import dev.otectus.mcacrime.state.world.CrimeWorldData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.server.players.PlayerList;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.fml.common.Mod;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * MCA Capitals royal decrees and MCA Blueprint prison recognition (0.7.8), exercised against the real
 * MCA, Capitals and MCA: Crime jars on a production Forge server, with real SRG-named Minecraft.
 *
 * <p>Two runs on one world. The first builds cells and has MCA's own scanner classify them, founds a
 * player-ruled capital on their village and drives every decree path through the real services. The
 * second, after a restart, checks that a prison and an open decree survived it, then that a capital
 * leaving its village voids what it decreed.
 */
@Mod("crime_capitals_checks")
public final class CapitalsRuntimeChecks {

    /** MCA's package roots, newest first: 7.7 moved it, and the harness runs against either. */
    private static final List<String> MCA_ROOTS = List.of("forge.net.conczin.mca.", "forge.net.mca.");
    private static final String CAP = "com.majesttyx.mcacapitals.";
    private static final ResourceLocation GUARD = new ResourceLocation("mca", "guard");
    private static final Path PHASE_MARK = Path.of("capitals-phase0.done");
    private static final Path SURVIVOR = Path.of("capitals-survivor.txt");

    private final List<String> results = new ArrayList<>();
    private final List<Waiting> waiting = new ArrayList<>();
    private final List<ServerPlayer> fixtures = new ArrayList<>();
    /** Every system-message key each fixture player was sent: the fastest way to see why a service refused. */
    private final Map<UUID, List<String>> told = new HashMap<>();
    /** What the first run leaves for the second: a prison room, and with Capitals an open decree. */
    private final Map<String, String> survivor = new java.util.LinkedHashMap<>();
    private MinecraftServer server;
    private ServerLevel level;
    private CrimeWorldData data;
    private int ticks;
    private int fixture;
    private boolean finished;

    // Phase-zero fixtures shared between checks.
    private BlockPos capitalRoom;
    private CrimeCommunityKey capitalVillage;
    private Object capital;
    private UUID capitalId;
    private ServerPlayer ruler;
    private Mob guard;

    private record Waiting(String name, int deadline, Supplier<String> problem) {
    }

    public CapitalsRuntimeChecks() {
        MinecraftForge.EVENT_BUS.addListener(this::started);
        MinecraftForge.EVENT_BUS.addListener(this::tick);
    }

    private void started(ServerStartedEvent event) {
        server = event.getServer();
    }

    private void tick(TickEvent.ServerTickEvent event) {
        // Twenty ticks in, so Capitals has reloaded its registry and MCA: Crime has bound to it.
        if (server == null || finished || event.phase != TickEvent.Phase.END || ++ticks < 20) {
            return;
        }
        if (ticks == 20) {
            level = server.overworld();
            data = CrimeWorldData.get(server);
            try {
                if (Files.exists(PHASE_MARK)) {
                    phaseOne();
                } else {
                    phaseZero();
                }
            } catch (Throwable failure) {
                results.add("FAIL harness: " + failure);
                failure.printStackTrace();
            }
        }
        poll();
        if (waiting.isEmpty()) {
            finish();
        }
    }

    private void finish() {
        finished = true;
        results.add("COMPLETE " + results.size() + " checks");
        fixtures.forEach(this::offline);
        try {
            if (!Files.exists(PHASE_MARK)) {
                List<String> lines = new ArrayList<>();
                survivor.forEach((key, value) -> lines.add(key + "=" + value));
                Files.write(SURVIVOR, lines);
                Files.writeString(PHASE_MARK, "done");
            }
            Files.write(Path.of("runtime-results.txt"), results);
        } catch (Exception failure) {
            throw new RuntimeException(failure);
        }
        results.forEach(System.out::println);
        server.halt(false);
    }

    private void check(String name, Runnable test) {
        try {
            test.run();
            results.add("PASS " + name);
        } catch (Throwable failure) {
            results.add("FAIL " + name + ": " + failure);
            failure.printStackTrace();
        }
    }

    /** A check that needs ticks to pass; {@code problem} answers null once it holds. */
    private void await(String name, int timeoutTicks, Supplier<String> problem) {
        waiting.add(new Waiting(name, ticks + timeoutTicks, problem));
    }

    private void poll() {
        for (Iterator<Waiting> it = waiting.iterator(); it.hasNext(); ) {
            Waiting check = it.next();
            String problem;
            try {
                problem = check.problem().get();
            } catch (Throwable failure) {
                failure.printStackTrace();
                results.add("FAIL " + check.name() + ": " + failure);
                it.remove();
                continue;
            }
            if (problem == null) {
                results.add("PASS " + check.name());
            } else if (ticks < check.deadline()) {
                continue;
            } else {
                results.add("FAIL " + check.name() + ": " + problem);
            }
            it.remove();
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    // ================================================================== phase zero

    private void phaseZero() {
        check("the mca bar and door tags carry the prison set beside vanilla's", this::tags);
        check("a cell of reinforced bars, a gapped bar and a cell door is offered and recorded as an MCA prison",
                () -> requirePrison(room(20, 20, 7, 0, 1, true, false)));
        check("a vanilla iron-bar cell still is", () -> requirePrison(room(-24, 20, 0, 8, 0, false, false)));
        check("five iron bars and three reinforced bars count as one requirement",
                () -> requirePrison(room(20, -24, 3, 5, 0, true, false)));
        check("seven bars are not a prison: the shipped count still applies",
                () -> requireNoPrison(room(-24, -24, 7, 0, 0, true, false)));
        check("an open cell door is still the cell's door", () -> requirePrison(room(64, 20, 8, 0, 0, true, true)));
        reloadThenRescan();
        check("a prison is left for the restart", () -> {
            BlockPos cell = room(-24, 64, 8, 0, 0, true, false);
            requirePrison(cell);
            survivor.put("room", cell.getX() + "," + cell.getY() + "," + cell.getZ());
        });
        if (!net.minecraftforge.fml.ModList.get().isLoaded(CapitalsBridge.MOD_ID)) {
            check("without MCA Capitals the bridge is absent and a decree is refused, not guessed", this::noCapitals);
            return;
        }
        check("the Capitals bridge binds every capability on a production server", this::bridgeBound);
        check("a capital founded on the cell's village is read through the bridge", this::foundCapital);
        if (capital == null) {
            results.add("FAIL harness: no capital, so no decree check can run");
            return;
        }
        check("Capitals counts the cell of Crime blocks as the capital's prison", this::capitalsPrison);
        check("the decree screen: hosted by a guard for the ruler only, issued and withdrawn, replays refused", this::screenRoute);
        check("the ruler issues a decree and the target is told and listed", this::issue);
        check("the same decree twice stacks nothing", this::duplicate);
        check("a player who does not rule the village cannot decree", this::notRuler);
        check("a knight of this capital cannot be named", this::knight);
        check("a lord by the capital's own record cannot be named", this::lord);
        check("a duke of another capital is a stranger here", this::foreignDuke);
        check("the village guard challenges on the decree and takes the fine; Heat and Karma untouched", this::finedAtChallenge);
        check("a guard of no village sees no decree", this::guardOfNowhere);
        check("succession leaves the decree standing; the former ruler cannot withdraw, the new one can", this::succession);
        check("raising the target to Noble voids the standing decree", this::ennobled);
        check("Capitals' own warrant is reported in /crime status, never enforced", this::capitalsWarrant);
        check("the chronicle line is written only when switched on", this::chronicle);
        check("a decree-only arrest carries the decree's term and serving it answers the decree", this::arrestAndServe);
        check("an operator's release hands the decree back unanswered", this::adminRelease);
        check("the decree table round-trips through the world save", this::roundTrip);
        check("an open decree is left for the restart", this::leaveSurvivor);
    }

    /** No Capitals: nothing binds, and a decree is refused because nobody can be shown to rule. */
    private void noCapitals() {
        require(CapitalsBridge.state() == CapitalsBridge.State.ABSENT, "bridge state " + CapitalsBridge.status());
        require(!CapitalsBridge.decreesReady(), "decrees ready with no Capitals");
        ServerPlayer would = realPlayer(level.getSharedSpawnPos().offset(-21, 65 - level.getSharedSpawnPos().getY(), 67));
        ServerPlayer target = realPlayer(would.blockPosition());
        var outcome = DecreeService.issue(would, target, DecreeReason.OTHER, DecreeProvenance.COMMAND);
        require(outcome.refusal() == DecreeRefusal.NOT_VERIFIABLE, "with no Capitals the issue said " + outcome.refusal());
        require(data.decrees().isEmpty(), "a decree was stored with no Capitals");
        require(told(would).isEmpty() && told(target).isEmpty(), "somebody was told of a decree that never was");
    }

    // ------------------------------------------------------------------ Blueprint recognition

    private void tags() {
        TagKey<Block> bars = TagKey.create(Registries.BLOCK, new ResourceLocation("mca", "iron_bars"));
        TagKey<Block> doors = TagKey.create(Registries.BLOCK, new ResourceLocation("mca", "iron_doors"));
        require(CrimeBlocks.REINFORCED_BARS.get().defaultBlockState().is(bars), "reinforced bars missing from mca:iron_bars");
        require(CrimeBlocks.REINFORCED_BARS_GAP.get().defaultBlockState().is(bars), "gapped bars missing from mca:iron_bars");
        require(CrimeBlocks.CELL_DOOR.get().defaultBlockState().is(doors), "cell door missing from mca:iron_doors");
        require(Blocks.IRON_BARS.defaultBlockState().is(bars), "vanilla iron bars dropped from mca:iron_bars");
        require(Blocks.IRON_DOOR.defaultBlockState().is(doors), "vanilla iron door dropped from mca:iron_doors");
    }

    /**
     * A closed room with a 5x5x3 interior on a stone floor at spawn plus (dx, dz): the requested bars in
     * three of its walls, a cell door or an iron door in the fourth, and a bed. Returns the interior
     * point the Blueprint would be used from.
     */
    private BlockPos room(int dx, int dz, int reinforced, int iron, int gapped, boolean cellDoor, boolean open) {
        BlockPos spawn = level.getSharedSpawnPos();
        BlockPos origin = new BlockPos(spawn.getX() + dx, 64, spawn.getZ() + dz);
        for (BlockPos pos : BlockPos.betweenClosed(origin.offset(-1, 0, -1), origin.offset(7, 5, 7))) {
            level.setBlock(pos, Blocks.AIR.defaultBlockState(), 2);
        }
        for (BlockPos pos : BlockPos.betweenClosed(origin.offset(-1, -1, -1), origin.offset(7, -1, 7))) {
            level.setBlock(pos, Blocks.STONE.defaultBlockState(), 2);
        }
        for (int x = 0; x <= 6; x++) {
            for (int z = 0; z <= 6; z++) {
                level.setBlock(origin.offset(x, 0, z), Blocks.STONE.defaultBlockState(), 2);
                level.setBlock(origin.offset(x, 4, z), Blocks.STONE.defaultBlockState(), 2);
                if (x == 0 || x == 6 || z == 0 || z == 6) {
                    for (int y = 1; y <= 3; y++) {
                        level.setBlock(origin.offset(x, y, z), Blocks.STONE.defaultBlockState(), 2);
                    }
                }
            }
        }
        // Bars at mid height in the north, west and east walls: fifteen slots.
        List<BlockPos> slots = new ArrayList<>();
        for (int i = 1; i <= 5; i++) {
            slots.add(origin.offset(i, 2, 0));
            slots.add(origin.offset(0, 2, i));
            slots.add(origin.offset(6, 2, i));
        }
        int slot = 0;
        for (int i = 0; i < reinforced; i++) {
            level.setBlock(slots.get(slot++), CrimeBlocks.REINFORCED_BARS.get().defaultBlockState(), 3);
        }
        for (int i = 0; i < gapped; i++) {
            level.setBlock(slots.get(slot++), CrimeBlocks.REINFORCED_BARS_GAP.get().defaultBlockState(), 3);
        }
        for (int i = 0; i < iron; i++) {
            level.setBlock(slots.get(slot++), Blocks.IRON_BARS.defaultBlockState(), 3);
        }
        Block door = cellDoor ? CrimeBlocks.CELL_DOOR.get() : Blocks.IRON_DOOR;
        BlockPos lower = origin.offset(3, 1, 6);
        BlockState base = door.defaultBlockState().setValue(DoorBlock.FACING, Direction.NORTH).setValue(DoorBlock.OPEN, open);
        level.setBlock(lower, base.setValue(DoorBlock.HALF, DoubleBlockHalf.LOWER), 2);
        level.setBlock(lower.above(), base.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER), 2);
        BlockState bed = Blocks.RED_BED.defaultBlockState().setValue(BedBlock.FACING, Direction.SOUTH);
        level.setBlock(origin.offset(1, 1, 1), bed.setValue(BedBlock.PART, BedPart.FOOT), 2);
        level.setBlock(origin.offset(1, 1, 2), bed.setValue(BedBlock.PART, BedPart.HEAD), 2);
        return origin.offset(3, 1, 3);
    }

    /** MCA's village store, under whichever package root this MCA build uses. */
    private Object villageManager() throws ReflectiveOperationException {
        for (String root : MCA_ROOTS) {
            try {
                return staticCall(root + "server.world.data.VillageManager", "get", level);
            } catch (ClassNotFoundException absent) {
                // the next root
            }
        }
        throw new ClassNotFoundException("MCA's VillageManager under any known package root");
    }

    /**
     * What the room came to. {@code offered} is what MCA's scanner said the room could be, or null on an
     * MCA that does not say (7.6 picks a type itself); {@code type} is what it recorded.
     */
    private record Scan(@javax.annotation.Nullable List<String> offered, String result, @javax.annotation.Nullable String type) {
        boolean offers(String wanted) {
            return offered == null || offered.contains(wanted);
        }

        @Override
        public String toString() {
            return (type == null ? "no building" : type) + " (" + result + (offered == null ? "" : ", offered " + offered) + ")";
        }
    }

    /**
     * Uses the Blueprint's "add room" from {@code interior}, exactly as MCA's own handler does. On MCA 7.7.1
     * that is a strict scan, and a room that fits several types asks the player to pick one, which here
     * is {@code choose}; MCA 7.6 has no such question and records the type it picks itself.
     */
    private Scan blueprint(BlockPos interior, String choose) {
        try {
            Object manager = villageManager();
            Method analyze = method(manager.getClass(), "analyzeBuilding", 2);
            if (analyze == null) {
                Object result = call(manager, "processBuilding", interior);
                return new Scan(null, String.valueOf(result), typeAt(manager, interior));
            }
            Object analysis = analyze.invoke(manager, interior, true);
            @SuppressWarnings("unchecked")
            List<String> offered = List.copyOf((List<String>) call(analysis, "matchingTypes"));
            boolean ambiguous = "SUCCESS".equals(String.valueOf(call(analysis, "result")))
                    && (boolean) call(analysis, "isAmbiguous");
            Object result;
            if (ambiguous) {
                if (!offered.contains(choose)) {
                    return new Scan(offered, "not offered", typeAt(manager, interior));
                }
                // ConfirmBuildingPolymorphMessage: the room is scanned again, then committed as chosen.
                result = call(manager, "commitBuilding", analyze.invoke(manager, interior, true), choose);
            } else {
                result = call(manager, "commitBuilding", analysis, null);
            }
            return new Scan(offered, String.valueOf(result), typeAt(manager, interior));
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError("MCA's scan could not be driven", failure);
        }
    }

    @javax.annotation.Nullable
    private String typeAt(Object manager, BlockPos interior) throws ReflectiveOperationException {
        Optional<?> building = buildingAt(manager, interior);
        return building.isEmpty() ? null : (String) call(building.get(), "getType");
    }

    private Optional<?> buildingAt(Object manager, BlockPos interior) throws ReflectiveOperationException {
        Optional<?> village = (Optional<?>) call(manager, "findNearestVillage", interior, 0);
        if (village.isEmpty()) {
            return Optional.empty();
        }
        return (Optional<?>) call(village.get(), "getBuildingAt", (Vec3i) interior);
    }

    /** Takes the recorded building out of its village, so the next scan classifies the room afresh. */
    private void forgetBuilding(BlockPos interior) throws ReflectiveOperationException {
        Optional<?> village = (Optional<?>) call(villageManager(), "findNearestVillage", interior, 0);
        if (village.isEmpty()) {
            return;
        }
        Optional<?> building = (Optional<?>) call(village.get(), "getBuildingAt", (Vec3i) interior);
        if (building.isPresent()) {
            call(village.get(), "removeBuilding", (int) call(building.get(), "getId"));
        }
    }

    private int villageIdAt(BlockPos interior) throws ReflectiveOperationException {
        Optional<?> village = (Optional<?>) call(villageManager(), "findNearestVillage", interior, 0);
        require(village.isPresent(), "no MCA village at " + interior);
        return (int) call(village.get(), "getId");
    }

    private void requirePrison(BlockPos interior) {
        Scan scan = blueprint(interior, "prison");
        require(scan.offers("prison") && "prison".equals(scan.type()), "MCA made the room " + scan);
    }

    private void requireNoPrison(BlockPos interior) {
        Scan scan = blueprint(interior, "prison");
        require(!"prison".equals(scan.type()) && (scan.offered() == null || !scan.offered().contains("prison")),
                "MCA offered or made a prison: " + scan);
    }

    private void reloadThenRescan() {
        BlockPos interior = room(-64, 20, 6, 0, 2, true, false);
        Scan before = blueprint(interior, "prison");
        var reload = server.reloadResources(server.getPackRepository().getSelectedIds());
        await("a prison stays a prison after /reload and a fresh rescan", 600, () -> {
            if (!reload.isDone()) {
                return "the reload is still running";
            }
            if (reload.isCompletedExceptionally()) {
                return "the reload failed";
            }
            if (!"prison".equals(before.type())) {
                return "before the reload the room was " + before;
            }
            try {
                forgetBuilding(interior);
            } catch (ReflectiveOperationException failure) {
                throw new AssertionError(failure);
            }
            Scan after = blueprint(interior, "prison");
            return after.offers("prison") && "prison".equals(after.type()) ? null : "after the reload: " + after;
        });
    }

    // ------------------------------------------------------------------ Capitals and decrees

    private void bridgeBound() {
        require(CapitalsBridge.state() == CapitalsBridge.State.FULL, "bridge state " + CapitalsBridge.status()
                + ", unbound " + CapitalsBridge.unresolvedMembers());
        require(CapitalsBridge.decreesReady(), "decrees not ready");
    }

    private void foundCapital() {
        try {
            BlockPos room = room(20, 64, 8, 0, 0, true, false);
            requirePrison(room);
            int village = villageIdAt(room);
            CrimeCommunityKey key = CrimeCommunityKey.of(level.dimension(), village).orElseThrow();
            ServerPlayer sovereign = realPlayer(room.offset(1, 0, 0));
            UUID id = UUID.randomUUID();
            Object record = newCapital(id, village, sovereign.getUUID());
            staticCall(CAP + "data.CapitalDataAccess", "markDirty", level);
            var view = CapitalsBridge.capitalAt(level, village);
            require(view.isAvailable(), "the bridge cannot see the capital: " + view.describe());
            require(view.orThrow().ruledBy(sovereign.getUUID()), "the player throne did not read back");
            require(DecreeService.rules(sovereign, key), "the ruler does not rule their own village");
            Mob villageGuard = mcaGuard(room.offset(-1, 0, 0));
            capitalRoom = room;
            capitalVillage = key;
            ruler = sovereign;
            capitalId = id;
            guard = villageGuard;
            capital = record;
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
        }
    }

    /**
     * The server half of the decree screen, which the menu action and the two packets drive: who may host
     * it, what it offers, and that an answer is checked against the session before Capitals is asked.
     */
    private void screenRoute() {
        ServerPlayer target = realPlayer(capitalRoom);
        Mob farmer = mcaVillager(capitalRoom.offset(1, 0, 1));
        try {
            require(DecreeMenuService.host(ruler, farmer).isEmpty(), "a villager with no office hosted the screen");
            require(DecreeMenuService.host(target, guard).isEmpty(), "a player who does not rule was offered the screen");
            DecreeMenuService.Menu menu = DecreeMenuService.open(ruler, guard);
            require(menu != null, "the village guard did not host the ruler's screen");
            DecreeMenuService.Candidate row = menu.candidates().stream()
                    .filter(candidate -> candidate.player().equals(target.getUUID())).findFirst()
                    .orElseThrow(() -> new AssertionError("the screen does not offer a player standing in the village"));
            require(row.eligible(), "the screen marks a commoner ineligible: " + row.reasonKey());
            require(menu.candidates().stream().noneMatch(candidate -> candidate.player().equals(ruler.getUUID())
                    && candidate.eligible()), "the screen offers the ruler to themselves");
            UUID nonce = UUID.randomUUID();
            var issued = DecreeMenuService.submit(ruler, menu.id(), menu.revision(), nonce, DecreeMenuService.Op.ISSUE,
                    target.getUUID(), DecreeReason.TREASON.id());
            require(issued.ok(), "the screen's issue was refused: " + issued.refusal());
            require(issued.decree().provenance() == DecreeProvenance.MENU, "provenance " + issued.decree().provenance());
            var replay = DecreeMenuService.submit(ruler, menu.id(), menu.revision(), nonce, DecreeMenuService.Op.ISSUE,
                    target.getUUID(), DecreeReason.TREASON.id());
            require(replay.refusal() == DecreeRefusal.STALE, "a replayed answer said " + replay.refusal());
            DecreeMenuService.Menu again = DecreeMenuService.open(ruler, guard);
            require(again.open().stream().anyMatch(open -> open.decree().equals(issued.decree().id())),
                    "the screen does not list the standing decree");
            var forged = DecreeMenuService.submit(ruler, again.id(), again.revision(), UUID.randomUUID(),
                    DecreeMenuService.Op.ISSUE, UUID.randomUUID(), DecreeReason.OTHER.id());
            require(forged.refusal() == DecreeRefusal.STALE, "a target the screen never offered got " + forged.refusal());
            var withdrawn = DecreeMenuService.submit(ruler, again.id(), again.revision(), UUID.randomUUID(),
                    DecreeMenuService.Op.WITHDRAW, issued.decree().id(), "");
            require(withdrawn.ok(), "the screen's withdrawal was refused: " + withdrawn.refusal());
            require(data.decree(issued.decree().id()).state() == DecreeState.WITHDRAWN, "not withdrawn");
        } finally {
            farmer.discard();
        }
    }

    /** Capitals reads MCA's prisons itself; the capital room is built of Crime's bars and cell door only. */
    @SuppressWarnings("unchecked")
    private void capitalsPrison() {
        try {
            require(Boolean.TRUE.equals(staticCall(CAP + "capital.CapitalBuildingService", "hasPrison", level, capital)),
                    "Capitals sees no prison in the capital");
            List<net.minecraft.world.phys.AABB> bounds = (List<net.minecraft.world.phys.AABB>) staticCall(
                    CAP + "capital.CapitalBuildingService", "getPrisonBounds", level, capital);
            net.minecraft.world.phys.Vec3 cell = net.minecraft.world.phys.Vec3.atCenterOf(capitalRoom);
            require(bounds.stream().anyMatch(box -> box.inflate(0.5).contains(cell)),
                    "no prison Capitals knows covers the Crime cell: " + bounds);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
        }
    }

    private Object newCapital(UUID id, int village, UUID sovereign) throws ReflectiveOperationException {
        Class<?> record = Class.forName(CAP + "capital.CapitalRecord");
        Object created = record.getConstructor(UUID.class, Integer.class).newInstance(id, village);
        call(created, "setVillageDimensionId", level.dimension().location().toString());
        call(created, "setState", enumConstant(CAP + "capital.CapitalState", "ACTIVE"));
        if (sovereign != null) {
            call(created, "setPlayerSovereign", true);
            call(created, "setPlayerSovereignId", sovereign);
        }
        staticCall(CAP + "capital.CapitalManager", "putCapital", created);
        return created;
    }

    private void grant(ServerPlayer player, Object inCapital, String title) throws ReflectiveOperationException {
        staticCall(CAP + "player.PlayerCapitalTitleService", "grantTitle", level, inCapital, player.getUUID(),
                enumConstant(CAP + "noble.NobleTitle", title));
    }

    private Decree issueOk(ServerPlayer target, DecreeReason reason) {
        var outcome = DecreeService.issue(ruler, target, reason, DecreeProvenance.COMMAND);
        require(outcome.ok(), "issue refused: " + outcome.refusal() + ", the ruler was told " + told(ruler));
        return outcome.decree();
    }

    private void issue() {
        ServerPlayer target = realPlayer(capitalRoom);
        Decree decree = issueOk(target, DecreeReason.TRESPASS);
        require(decree.community().equals(capitalVillage), "the decree is for " + decree.community());
        require(decree.capitalId().equals(capitalId) && decree.issuedBy().equals(ruler.getUUID()),
                "the decree names the wrong capital or issuer");
        require(told(ruler).contains("mcacrime.decree.issued"), "the ruler was not told: " + told(ruler));
        require(told(target).contains("mcacrime.decree.notice.issued"), "the target was not told: " + told(target));
        require(DecreeService.inForce(server, target.getUUID(), capitalVillage).isPresent(), "not in force");
        require(DecreeService.statusLines(target).stream().anyMatch(line -> key(line).equals("mcacrime.command.status.decree")),
                "/crime status does not list it");
        require(CrimeState.getHeat(target) == 0 && CrimeState.getKarma(target) == 0 && !CrimeState.isWanted(target),
                "a decree changed Heat, Karma or the Wanted flag");
        require(data.warrant(target.getUUID()) == null, "a decree opened a bounty warrant");
        require(data.actionableFor(target.getUUID()).isEmpty(), "a decree filed a case");
    }

    private void duplicate() {
        ServerPlayer target = realPlayer(capitalRoom);
        issueOk(target, DecreeReason.DISORDER);
        var again = DecreeService.issue(ruler, target, DecreeReason.TREASON, DecreeProvenance.MENU);
        require(again.refusal() == DecreeRefusal.DUPLICATE, "second issue said " + again.refusal());
        require(data.decreesOf(target.getUUID()).size() == 1, "two decree rows for one target");
    }

    private void notRuler() {
        ServerPlayer pretender = realPlayer(capitalRoom);
        ServerPlayer target = realPlayer(capitalRoom);
        var outcome = DecreeService.issue(pretender, target, DecreeReason.OTHER, DecreeProvenance.COMMAND);
        require(outcome.refusal() == DecreeRefusal.NOT_SOVEREIGN, "a non-ruler got " + outcome.refusal());
        require(data.decreesOf(target.getUUID()).isEmpty(), "a refused decree was stored");
    }

    private void knight() {
        try {
            ServerPlayer knight = realPlayer(capitalRoom);
            grant(knight, capital, "KNIGHT");
            var outcome = DecreeService.issue(ruler, knight, DecreeReason.OTHER, DecreeProvenance.COMMAND);
            require(outcome.refusal() == DecreeRefusal.TARGET_NOBLE, "a knight got " + outcome.refusal());
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
        }
    }

    private void lord() {
        try {
            ServerPlayer lord = realPlayer(capitalRoom);
            call(capital, "addLord", lord.getUUID(), false);
            var outcome = DecreeService.issue(ruler, lord, DecreeReason.OTHER, DecreeProvenance.COMMAND);
            require(outcome.refusal() == DecreeRefusal.TARGET_NOBLE, "a lord got " + outcome.refusal());
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
        }
    }

    private void foreignDuke() {
        try {
            Object elsewhere = newCapital(UUID.randomUUID(), 9_999, null);
            ServerPlayer duke = realPlayer(capitalRoom);
            grant(duke, elsewhere, "DUKE");
            issueOk(duke, DecreeReason.OTHER);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
        }
    }

    private void finedAtChallenge() {
        ServerPlayer target = realPlayer(capitalRoom);
        target.getInventory().add(new ItemStack(Items.EMERALD, 40));
        Decree decree = issueOk(target, DecreeReason.DEFIANCE);
        LegalDecision decision = JusticeService.forGuard(level, guard, target);
        require(decision.basis().contains(LegalDecision.Basis.ROYAL_DECREE) && decree.id().equals(decision.decree()),
                "the guard's decision has no decree: " + decision.basis());
        require(GuardChallengeService.challenge(level, guard, target), "the guard did not challenge; told " + told(target));
        GuardChallenge challenge = GuardChallengeService.open(target.getUUID());
        require(challenge != null && challenge.canPay() && challenge.assessedFine() == 32L,
                "the challenge offers no decree fine: " + challenge);
        var payment = GuardChallengeService.payFine(target, challenge.encounterId(), challenge.revision());
        require(payment.paid(), "payment refused: " + payment.messageKey() + "; told " + told(target));
        require(data.decree(decree.id()).state() == DecreeState.FINED, "decree is " + data.decree(decree.id()).state());
        int emeralds = target.getInventory().countItem(Items.EMERALD);
        require(emeralds == 8, "expected 8 emeralds left, found " + emeralds);
        require(DecreeService.inForce(server, target.getUUID(), capitalVillage).isEmpty(), "still in force after paying");
        require(CrimeState.getHeat(target) == 0 && CrimeState.getKarma(target) == 0, "paying changed Heat or Karma");
        require(GuardChallengeService.open(target.getUUID()) == null, "the challenge stayed open after paying");
    }

    private void guardOfNowhere() {
        ServerPlayer target = realPlayer(capitalRoom);
        issueOk(target, DecreeReason.OTHER);
        BlockPos spawn = level.getSharedSpawnPos();
        Mob stranger = mcaGuard(new BlockPos(spawn.getX() - 140, 64, spawn.getZ() - 140));
        LegalDecision decision = JusticeService.forGuard(level, stranger, target);
        require(decision.jurisdiction() == null, "the stranger guard belongs to " + decision.jurisdiction());
        require(!decision.basis().contains(LegalDecision.Basis.ROYAL_DECREE), "a guard of no village saw the decree");
        stranger.discard();
    }

    private void succession() {
        try {
            ServerPlayer target = realPlayer(capitalRoom);
            Decree decree = issueOk(target, DecreeReason.TRESPASS);
            ServerPlayer heir = realPlayer(capitalRoom.offset(0, 0, 1));
            call(capital, "setPlayerSovereignId", heir.getUUID());
            try {
                require(DecreeService.inForce(server, target.getUUID(), capitalVillage).isPresent(),
                        "succession ended the decree");
                var former = DecreeService.withdraw(ruler, target.getUUID(), DecreeProvenance.COMMAND);
                require(former.refusal() == DecreeRefusal.NOT_SOVEREIGN, "the former ruler got " + former.refusal());
                var current = DecreeService.withdraw(heir, target.getUUID(), DecreeProvenance.COMMAND);
                require(current.ok(), "the new ruler was refused: " + current.refusal());
                require(data.decree(decree.id()).state() == DecreeState.WITHDRAWN, "not withdrawn");
                require(told(target).contains("mcacrime.decree.notice.withdrawn"), "the target was not told: " + told(target));
            } finally {
                call(capital, "setPlayerSovereignId", ruler.getUUID());
            }
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
        }
    }

    private void ennobled() {
        try {
            ServerPlayer target = realPlayer(capitalRoom);
            Decree decree = issueOk(target, DecreeReason.DISORDER);
            grant(target, capital, "LORD");
            DecreeService.sweep(server);
            Decree after = data.decree(decree.id());
            require(after.state() == DecreeState.VOID && "target_ennobled".equals(after.note()),
                    "after ennoblement the decree is " + after.state() + " " + after.note());
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
        }
    }

    private void capitalsWarrant() {
        try {
            ServerPlayer target = realPlayer(capitalRoom);
            require(Boolean.TRUE.equals(staticCall(CAP + "data.CapitalPlayerWarrantDataAccess", "issueWarrant", level,
                    target.getUUID(), capitalId)), "Capitals refused its own warrant");
            require(DecreeService.statusLines(target).stream()
                            .anyMatch(line -> key(line).equals("mcacrime.command.status.capitals_warrant")),
                    "Capitals' own warrant is not reported");
            require(DecreeService.inForce(server, target.getUUID(), capitalVillage).isEmpty(),
                    "Capitals' warrant became a Crime decree");
            require(!JusticeService.forGuard(level, guard, target).mayChallenge(), "a guard acted on Capitals' warrant");
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
        }
    }

    @SuppressWarnings("unchecked")
    private void chronicle() {
        try {
            ServerPlayer quiet = realPlayer(capitalRoom);
            issueOk(quiet, DecreeReason.OTHER);
            List<String> entries = (List<String>) call(capital, "getChronicleEntries");
            require(entries.stream().noneMatch(entry -> entry.contains(quiet.getGameProfile().getName())),
                    "a chronicle line was written while chronicle is off");
            McaCrimeConfig.COMMON.decreesChronicle.set(true);
            try {
                ServerPlayer recorded = realPlayer(capitalRoom);
                issueOk(recorded, DecreeReason.TREASON);
                entries = (List<String>) call(capital, "getChronicleEntries");
                require(entries.stream().anyMatch(entry -> entry.contains(recorded.getGameProfile().getName())
                        && entry.contains("treason")), "no chronicle line names the target: " + entries);
            } finally {
                McaCrimeConfig.COMMON.decreesChronicle.set(false);
            }
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
        }
    }

    /** An operator jail well inside the spawn chunks, floored, away from every fixture room. */
    private JailAnchor jail() {
        BlockPos spawn = level.getSharedSpawnPos();
        BlockPos at = new BlockPos(spawn.getX() - 60, 64, spawn.getZ() - 60);
        for (BlockPos pos : BlockPos.betweenClosed(at.offset(-3, -1, -3), at.offset(3, -1, 3))) {
            level.setBlock(pos, Blocks.STONE.defaultBlockState(), 2);
        }
        JailAnchor anchor = new JailAnchor(at, level.dimension().location(), 3);
        JailRegistry.assign(server, anchor);
        return anchor;
    }

    /** The village guard arrests {@code target} on the decree alone, completing at once by the teleport path. */
    private UUID arrestOnDecree(ServerPlayer target, Decree decree) {
        jail();
        int escort = McaCrimeConfig.COMMON.arrestEscortTimeoutTicks.get();
        McaCrimeConfig.COMMON.arrestEscortTimeoutTicks.set(0);
        try {
            ArrestService.Outcome outcome = ArrestService.arrest(target, guard, ArrestService.Cause.GUARD_INITIATED);
            require(outcome == ArrestService.Outcome.ARRESTED, "a decree-only arrest was " + outcome + "; told " + told(target));
        } finally {
            McaCrimeConfig.COMMON.arrestEscortTimeoutTicks.set(escort);
        }
        require(JailService.isJailed(target), "the arrest did not jail");
        UUID sentence = data.decree(decree.id()).sentenceId();
        require(sentence != null, "the decree is not bound to the arrest's sentence");
        return sentence;
    }

    private void arrestAndServe() {
        ServerPlayer target = realPlayer(capitalRoom);
        Decree decree = issueOk(target, DecreeReason.TRESPASS);
        try {
            arrestOnDecree(target, decree);
            long term = JailService.remainingTicks(target);
            require(term >= McaCrimeConfig.COMMON.decreesSentenceTicks.get(), "the term " + term + " is under the decree's floor");
            JailService.release(target, ReleaseReason.SENTENCE_SERVED);
            Decree after = data.decree(decree.id());
            require(after.state() == DecreeState.SERVED, "after serving the decree is " + after.state());
            require(told(target).contains("mcacrime.decree.notice.served"), "the target was not told: " + told(target));
            require(CrimeState.getKarma(target) == 0, "serving a decree changed Karma");
        } finally {
            ArrestStates.clear(target);
            offline(target); // a released prisoner left standing on the anchor would block the next intake
        }
    }

    private void adminRelease() {
        ServerPlayer target = realPlayer(capitalRoom);
        Decree decree = issueOk(target, DecreeReason.DISORDER);
        try {
            arrestOnDecree(target, decree);
            JailService.release(target, ReleaseReason.ADMIN);
            Decree after = data.decree(decree.id());
            require(after.open() && after.sentenceId() == null, "after an operator's release the decree is "
                    + after.state() + " bound to " + after.sentenceId());
        } finally {
            ArrestStates.clear(target);
            offline(target);
        }
    }

    private void roundTrip() {
        CompoundTag saved = ((net.minecraft.world.level.saveddata.SavedData) data).save(new CompoundTag());
        CrimeWorldData loaded = CrimeWorldData.load(saved);
        require(!data.decrees().isEmpty(), "nothing to round-trip");
        require(loaded.decrees().equals(data.decrees()), "decrees changed across a save");
    }

    private void leaveSurvivor() {
        try {
            ServerPlayer target = realPlayer(capitalRoom);
            Decree decree = issueOk(target, DecreeReason.DEFIANCE);
            staticCall(CAP + "data.CapitalDataAccess", "markDirty", level);
            ((net.minecraft.world.level.saveddata.SavedData) data).setDirty();
            survivor.put("decree", decree.id().toString());
            survivor.put("capital", capitalId.toString());
        } catch (Exception failure) {
            throw new AssertionError(failure);
        }
    }

    // ================================================================== phase one, after a restart

    private void phaseOne() {
        Map<String, String> saved = new HashMap<>();
        try {
            for (String line : Files.readAllLines(SURVIVOR)) {
                int split = line.indexOf('=');
                if (split > 0) {
                    saved.put(line.substring(0, split), line.substring(split + 1));
                }
            }
        } catch (Exception failure) {
            results.add("FAIL harness: the first run left nothing: " + failure);
            return;
        }
        String[] at = saved.getOrDefault("room", "").split(",");
        check("a prison is still a prison after a restart", () -> {
            require(at.length == 3, "the first run recorded no prison");
            BlockPos room = new BlockPos(Integer.parseInt(at[0]), Integer.parseInt(at[1]), Integer.parseInt(at[2]));
            try {
                Object building = buildingAt(villageManager(), room).orElseThrow(() -> new AssertionError("no building"));
                require("prison".equals(call(building, "getType")), "the building is " + call(building, "getType"));
            } catch (ReflectiveOperationException failure) {
                throw new AssertionError(failure);
            }
        });
        if (!net.minecraftforge.fml.ModList.get().isLoaded(CapitalsBridge.MOD_ID)) {
            return;
        }
        if (!saved.containsKey("decree")) {
            results.add("FAIL harness: the first run left no open decree");
            return;
        }
        UUID decreeId = UUID.fromString(saved.get("decree"));
        UUID survivorCapital = UUID.fromString(saved.get("capital"));
        check("an open decree and its capital survive the restart and stand again", () -> {
            Decree decree = data.decree(decreeId);
            require(decree != null && decree.open(), "the decree did not survive: " + decree);
            require(CapitalsBridge.decreesReady(), "the bridge did not bind after the restart: " + CapitalsBridge.status());
            require(DecreeService.inForce(server, decree.offender(), decree.community()).isPresent(),
                    "the surviving decree is not in force: " + DecreeService.standing(server, decree));
        });
        check("a capital that leaves its village voids its decrees", () -> {
            try {
                newCapital(UUID.randomUUID(), 9_998, null); // the registry is not empty, so the absence is real
                staticCall(CAP + "capital.CapitalManager", "removeCapital", survivorCapital);
                DecreeService.sweep(server);
                Decree decree = data.decree(decreeId);
                require(decree.state() == DecreeState.VOID && "capital_gone".equals(decree.note()),
                        "after the capital left, the decree is " + decree.state() + " " + decree.note());
            } catch (ReflectiveOperationException failure) {
                throw new AssertionError(failure);
            }
        });
    }

    // ================================================================== fixtures and reflection

    private static String key(Component line) {
        return line.getContents() instanceof TranslatableContents contents ? contents.getKey() : line.getString();
    }

    private List<String> told(ServerPlayer player) {
        return told.getOrDefault(player.getUUID(), List.of());
    }

    /**
     * A real server player, not a {@code FakePlayer}: decrees, challenges and arrests only run for real
     * ones. Nothing it is sent goes anywhere, but every system-message key is recorded.
     */
    private ServerPlayer realPlayer(BlockPos at) {
        GameProfile profile = new GameProfile(UUID.randomUUID(), "Subject" + (++fixture));
        ServerPlayer player = new ServerPlayer(server, level, profile) {
            @Override
            public void sendSystemMessage(Component message, boolean overlay) {
                told.computeIfAbsent(getUUID(), id -> new ArrayList<>()).add(key(message));
                super.sendSystemMessage(message, overlay);
            }
        };
        net.minecraft.network.Connection connection = new net.minecraft.network.Connection(PacketFlow.SERVERBOUND) {
            @Override public void send(Packet<?> packet) { }
            @Override public void send(Packet<?> packet, @javax.annotation.Nullable net.minecraft.network.PacketSendListener listener) { }
        };
        new io.netty.channel.embedded.EmbeddedChannel(connection);
        player.connection = new ServerGamePacketListenerImpl(server, connection, player) {
            @Override public void send(Packet<?> packet) { }
            @Override public void send(Packet<?> packet, @javax.annotation.Nullable net.minecraft.network.PacketSendListener listener) { }
        };
        player.setPos(at.getX() + .5, at.getY(), at.getZ() + .5);
        // New server players have a grace period; these fixtures represent players already playing.
        net.minecraftforge.fml.util.ObfuscationReflectionHelper.setPrivateValue(ServerPlayer.class, player, 0, "f_8921_");
        level.addNewPlayer(player);
        playerList().add(player);
        onlinePlayers().put(player.getUUID(), player);
        fixtures.add(player);
        require(CrimeCapabilities.get(player).isPresent(), "player capability missing");
        return player;
    }

    private void offline(ServerPlayer player) {
        playerList().remove(player);
        onlinePlayers().remove(player.getUUID(), player);
        if (level.getEntity(player.getId()) == player) {
            level.removePlayerImmediately(player, Entity.RemovalReason.DISCARDED);
        }
    }

    @SuppressWarnings("unchecked")
    private List<ServerPlayer> playerList() {
        for (java.lang.reflect.Field field : PlayerList.class.getDeclaredFields()) {
            if (!(field.getGenericType() instanceof java.lang.reflect.ParameterizedType type)) continue;
            if (type.getRawType() != List.class || !Arrays.equals(type.getActualTypeArguments(),
                    new java.lang.reflect.Type[]{ServerPlayer.class})) continue;
            try {
                field.setAccessible(true);
                return (List<ServerPlayer>) field.get(server.getPlayerList());
            } catch (ReflectiveOperationException failure) {
                throw new AssertionError(failure);
            }
        }
        throw new AssertionError("server player list not found");
    }

    @SuppressWarnings("unchecked")
    private Map<UUID, ServerPlayer> onlinePlayers() {
        for (java.lang.reflect.Field field : PlayerList.class.getDeclaredFields()) {
            if (!(field.getGenericType() instanceof java.lang.reflect.ParameterizedType type)) continue;
            if (type.getRawType() != Map.class || !Arrays.equals(type.getActualTypeArguments(),
                    new java.lang.reflect.Type[]{UUID.class, ServerPlayer.class})) continue;
            try {
                field.setAccessible(true);
                return (Map<UUID, ServerPlayer>) field.get(server.getPlayerList());
            } catch (ReflectiveOperationException failure) {
                throw new AssertionError(failure);
            }
        }
        throw new AssertionError("server player lookup map not found");
    }

    /** A real MCA guard, standing still. */
    private Mob mcaGuard(BlockPos pos) {
        Mob mob = mcaVillager(pos);
        require(McaCompat.setVillagerProfession(mob, GUARD), "the guard fixture could not be made a guard");
        require(McaCompat.isGuard(mob), "the guard fixture is not a guard");
        return mob;
    }

    /** An adult MCA villager standing still at {@code pos}, resolved by name: no MCA type is linked. */
    @SuppressWarnings("unchecked")
    private Mob mcaVillager(BlockPos pos) {
        var type = (EntityType<? extends Mob>) net.minecraftforge.registries.ForgeRegistries.ENTITY_TYPES
                .getValue(new ResourceLocation("mca", "male_villager"));
        require(type != null, "MCA's male_villager type is not registered");
        Mob mob = type.create(level);
        require(mob != null, "MCA villager could not be created");
        mob.moveTo(pos.getX() + .5, pos.getY(), pos.getZ() + .5, 0, 0);
        level.addFreshEntity(mob);
        ((net.minecraft.world.entity.npc.Villager) mob).setAge(0);
        mob.setNoAi(true);
        require(McaCompat.isMcaVillager(mob), "fixture is not an MCA villager");
        return mob;
    }

    @javax.annotation.Nullable
    private static Method method(Class<?> type, String name, int arity) {
        for (Method method : type.getMethods()) {
            if (method.getName().equals(name) && method.getParameterCount() == arity) {
                return method;
            }
        }
        return null;
    }

    private static Object staticCall(String className, String name, Object... args) throws ReflectiveOperationException {
        return invoke(Class.forName(className), null, name, args);
    }

    private static Object call(Object target, String name, Object... args) throws ReflectiveOperationException {
        return invoke(target.getClass(), target, name, args);
    }

    /** The one public method of that name and arity whose parameters accept {@code args}. */
    private static Object invoke(Class<?> type, Object target, String name, Object... args) throws ReflectiveOperationException {
        for (Method method : type.getMethods()) {
            if (method.getName().equals(name) && method.getParameterCount() == args.length
                    && java.lang.reflect.Modifier.isStatic(method.getModifiers()) == (target == null)
                    && accepts(method.getParameterTypes(), args)) {
                return method.invoke(target, args);
            }
        }
        throw new NoSuchMethodException(type.getName() + "#" + name + "/" + args.length);
    }

    private static boolean accepts(Class<?>[] parameters, Object[] args) {
        for (int i = 0; i < parameters.length; i++) {
            Class<?> parameter = parameters[i].isPrimitive() ? boxed(parameters[i]) : parameters[i];
            if (args[i] == null ? parameters[i].isPrimitive() : !parameter.isInstance(args[i])) {
                return false;
            }
        }
        return true;
    }

    private static Class<?> boxed(Class<?> primitive) {
        if (primitive == int.class) return Integer.class;
        if (primitive == boolean.class) return Boolean.class;
        if (primitive == long.class) return Long.class;
        if (primitive == double.class) return Double.class;
        if (primitive == float.class) return Float.class;
        return primitive;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Object enumConstant(String className, String name) throws ReflectiveOperationException {
        return Enum.valueOf((Class) Class.forName(className), name);
    }
}
