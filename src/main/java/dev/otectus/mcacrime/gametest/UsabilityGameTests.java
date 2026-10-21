package dev.otectus.mcacrime.gametest;

import com.mojang.authlib.GameProfile;
import dev.otectus.mcacrime.McaCrime;
import dev.otectus.mcacrime.action.ActionSessionManager;
import dev.otectus.mcacrime.action.CancelReason;
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
import dev.otectus.mcacrime.item.CrimeItems;
import dev.otectus.mcacrime.job.CriminalJob;
import dev.otectus.mcacrime.job.WorldCriminalJobService;
import dev.otectus.mcacrime.ledger.CapitalSentenceService;
import dev.otectus.mcacrime.ledger.CrimeRecord;
import dev.otectus.mcacrime.ledger.Resolution;
import dev.otectus.mcacrime.ledger.SentenceAssignmentService;
import dev.otectus.mcacrime.state.world.CrimeWorldData;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;
import java.util.UUID;

/**
 * 0.8.0 usability on a real NeoForge server with a real MCA (workstreams A and B).
 *
 * <p>Targeting: the villager under the crosshair is the one a command changes, a box corner or a wall
 * never selects anybody, a wrong kind is named rather than replaced, ambiguity asks, and the console must
 * name its target. The guillotine: placement explains itself, a stale fall cannot borrow a newer order, a
 * blade longer than the order window still strikes inside it, and breaking the lower half takes the frame
 * down with it. The Forge build runs the same cases (and the vanilla-villager one, which needs MCA's
 * villager conversion off) in {@code tools/usability}.
 */
@GameTestHolder(McaCrime.MOD_ID)
@PrefixGameTestTemplate(false)
public final class UsabilityGameTests {

    private static final UUID GUARD = UUID.randomUUID();

    private UsabilityGameTests() {
    }

    // --- fixtures ------------------------------------------------------------------------------------

    /** A player built rather than placed (see DetentionDeviceGameTests#mockPlayer), recording what it is told. */
    private static ServerPlayer player(GameTestHelper helper, Vec3 relative, float yaw, List<String> heard) {
        ServerLevel level = helper.getLevel();
        GameProfile profile = new GameProfile(UUID.randomUUID(), "mcacrime-usability");
        ServerPlayer player = new ServerPlayer(level.getServer(), level, profile, ClientInformation.createDefault());
        player.connection = new ServerGamePacketListenerImpl(level.getServer(), new Connection(PacketFlow.SERVERBOUND),
                player, CommonListenerCookie.createInitial(profile, false)) {
            @Override
            public void send(Packet<?> packet) {
                if (packet instanceof ClientboundSystemChatPacket chat) {
                    heard.add(keyOf(chat.content()));
                }
            }

            @Override
            public void send(Packet<?> packet, @org.jetbrains.annotations.Nullable net.minecraft.network.PacketSendListener listener) {
                send(packet);
            }
        };
        Vec3 abs = helper.absoluteVec(relative);
        player.moveTo(abs.x, abs.y, abs.z, yaw, 0.0F);
        return player;
    }

    private static String keyOf(Component component) {
        if (component.getContents() instanceof TranslatableContents translatable) {
            return translatable.getKey();
        }
        for (Component sibling : component.getSiblings()) {
            String key = keyOf(sibling);
            if (!key.isEmpty()) {
                return key;
            }
        }
        return "";
    }

    @SuppressWarnings("unchecked")
    private static Mob mca(GameTestHelper helper, Vec3 relative, String path) {
        EntityType<? extends Mob> type = (EntityType<? extends Mob>) BuiltInRegistries.ENTITY_TYPE
                .getOptional(ResourceLocation.fromNamespaceAndPath("mca", path)).orElseThrow();
        Mob mob = helper.spawn(type, relative);
        mob.setNoAi(true);
        if (mob instanceof net.minecraft.world.entity.AgeableMob ageable) {
            ageable.setAge(0); // a created MCA villager is not finalized as an adult on its own
        }
        return mob;
    }

    private static Mob villager(GameTestHelper helper, double x, double z) {
        return mca(helper, new Vec3(x, 2.0D, z), "male_villager");
    }

    private static void aim(ServerPlayer player, LivingEntity target) {
        player.lookAt(EntityAnchorArgument.Anchor.EYES, target.getBoundingBox().getCenter());
    }

    private static void lookUp(ServerPlayer player) {
        player.lookAt(EntityAnchorArgument.Anchor.EYES, player.getEyePosition().add(0.0D, 10.0D, 0.0D));
    }

    private static int run(ServerPlayer player, int permission, String command) {
        return execute(player.createCommandSourceStack().withPermission(permission), command);
    }

    /** 1.21.1's performPrefixedCommand returns nothing, so the dispatcher is asked for the result code. */
    private static int execute(net.minecraft.commands.CommandSourceStack source, String command) {
        try {
            return source.getServer().getCommands().getDispatcher().execute(command, source);
        } catch (com.mojang.brigadier.exceptions.CommandSyntaxException refused) {
            McaCrime.LOGGER.info("usability gametest: '{}' was refused: {}", command, refused.getMessage());
            return 0;
        }
    }

    private static String job(GameTestHelper helper, LivingEntity villager) {
        return WorldCriminalJobService.of(helper.getLevel().getServer()).get(villager.getUUID()).id();
    }

    private static void reset(GameTestHelper helper, LivingEntity... villagers) {
        for (LivingEntity villager : villagers) {
            WorldCriminalJobService.of(helper.getLevel().getServer()).tryAssign(villager.getUUID(), CriminalJob.NONE, true);
        }
    }

    // --- targeting -----------------------------------------------------------------------------------

    @GameTest(template = "arena", timeoutTicks = 100, batch = "usability_targeting")
    public static void theAimedVillagerIsChangedNotTheCloserBystander(GameTestHelper helper) {
        List<String> heard = new ArrayList<>();
        ServerPlayer operator = player(helper, new Vec3(3.5D, 2.0D, 7.5D), -90.0F, heard);
        Mob aimed = villager(helper, 8.5D, 7.5D);
        Mob bystander = villager(helper, 2.0D, 7.5D);
        helper.runAfterDelay(2, () -> {
            aim(operator, aimed);
            helper.assertTrue(run(operator, 4, "crime job assign fence") == 1, "assignment failed: " + heard);
            helper.assertTrue("fence".equals(job(helper, aimed)), "the aimed villager is " + job(helper, aimed));
            helper.assertTrue("none".equals(job(helper, bystander)), "the bystander became " + job(helper, bystander));
            reset(helper, aimed, bystander);
            helper.succeed();
        });
    }

    @GameTest(template = "arena", timeoutTicks = 100, batch = "usability_targeting")
    public static void theSearchBoxCornerNeverSelectsAnybody(GameTestHelper helper) {
        List<String> heard = new ArrayList<>();
        ServerPlayer operator = player(helper, new Vec3(1.5D, 2.0D, 1.5D), -90.0F, heard);
        Mob corner = villager(helper, 9.0D, 9.0D);
        helper.runAfterDelay(2, () -> {
            lookUp(operator);
            helper.assertTrue(run(operator, 4, "crime job assign fence") == 0, "the corner villager was selected");
            helper.assertTrue("none".equals(job(helper, corner)), "the corner villager became " + job(helper, corner));
            helper.assertTrue(heard.contains("mcacrime.target.none.operator"), "said " + heard);
            helper.succeed();
        });
    }

    @GameTest(template = "arena", timeoutTicks = 100, batch = "usability_targeting")
    public static void aVillagerBehindAWallIsObstructedNotSelected(GameTestHelper helper) {
        List<String> heard = new ArrayList<>();
        ServerPlayer operator = player(helper, new Vec3(3.5D, 2.0D, 7.5D), -90.0F, heard);
        Mob walled = villager(helper, 6.5D, 7.5D);
        helper.setBlock(new BlockPos(5, 2, 7), Blocks.STONE.defaultBlockState());
        helper.setBlock(new BlockPos(5, 3, 7), Blocks.STONE.defaultBlockState());
        helper.runAfterDelay(2, () -> {
            aim(operator, walled);
            helper.assertTrue(run(operator, 4, "crime job assign fence") == 0, "the walled villager was selected");
            helper.assertTrue("none".equals(job(helper, walled)), "the walled villager became " + job(helper, walled));
            helper.assertTrue(heard.contains("mcacrime.target.obstructed"), "said " + heard);
            helper.succeed();
        });
    }

    @GameTest(template = "arena", timeoutTicks = 100, batch = "usability_targeting")
    public static void anAimedWrongKindIsNamedAndNothingBesideItChanges(GameTestHelper helper) {
        List<String> heard = new ArrayList<>();
        ServerPlayer operator = player(helper, new Vec3(3.5D, 2.0D, 7.5D), -90.0F, heard);
        LivingEntity trader = helper.spawn(EntityType.PIG, new Vec3(6.5D, 2.0D, 7.5D));
        ((Mob) trader).setNoAi(true);
        Mob zombie = mca(helper, new Vec3(6.5D, 2.0D, 11.5D), "male_zombie_villager");
        Mob beside = villager(helper, 5.0D, 4.5D); // off both sight lines: beside the targets, never in front
        helper.runAfterDelay(2, () -> {
            aim(operator, trader);
            helper.assertTrue(run(operator, 4, "crime job assign fence") == 0, "a job was assigned");
            helper.assertTrue(heard.contains("mcacrime.target.wrong_kind"), "the pig said " + heard);
            heard.clear();
            aim(operator, zombie);
            helper.assertTrue(run(operator, 4, "crime job assign fence") == 0, "a job was assigned");
            helper.assertTrue(heard.contains("mcacrime.target.wrong_kind"), "zombie said " + heard);
            helper.assertTrue("none".equals(job(helper, beside)), "the villager beside them was changed");
            helper.succeed();
        });
    }

    @GameTest(template = "arena", timeoutTicks = 100, batch = "usability_targeting")
    public static void severalVillagersInViewAskWhichOneButASingleOneIsTaken(GameTestHelper helper) {
        List<String> heard = new ArrayList<>();
        ServerPlayer operator = player(helper, new Vec3(3.5D, 2.0D, 7.5D), -90.0F, heard);
        Mob first = villager(helper, 6.5D, 10.5D);
        Mob second = villager(helper, 7.5D, 4.5D);
        helper.runAfterDelay(2, () -> {
            lookUp(operator);
            helper.assertTrue(run(operator, 4, "crime job assign fence") == 0, "an ambiguous selection resolved");
            helper.assertTrue(heard.contains("mcacrime.target.ambiguous"), "said " + heard);
            second.discard();
            heard.clear();
            helper.assertTrue(run(operator, 4, "crime job assign fence") == 1, "the single villager was refused: " + heard);
            helper.assertTrue("fence".equals(job(helper, first)), "the single villager is " + job(helper, first));
            reset(helper, first);
            helper.succeed();
        });
    }

    @GameTest(template = "arena", timeoutTicks = 100, batch = "usability_targeting")
    public static void theConsoleMustNameItsTargetAndThenGetsExactlyThatOne(GameTestHelper helper) {
        Mob named = villager(helper, 7.5D, 7.5D);
        helper.runAfterDelay(2, () -> {
            List<String> said = new ArrayList<>();
            net.minecraft.commands.CommandSource recorder = new net.minecraft.commands.CommandSource() {
                @Override
                public void sendSystemMessage(Component component) {
                    said.add(keyOf(component) + "=" + component.getString());
                }

                @Override
                public boolean acceptsSuccess() {
                    return true;
                }

                @Override
                public boolean acceptsFailure() {
                    return true;
                }

                @Override
                public boolean shouldInformAdmins() {
                    return false;
                }
            };
            var console = new net.minecraft.commands.CommandSourceStack(recorder, Vec3.ZERO, net.minecraft.world.phys.Vec2.ZERO,
                    helper.getLevel(), 4, "Console", Component.literal("Console"), helper.getLevel().getServer(), null);
            helper.assertTrue(execute(console, "crime job assign fence") == 0, "implicit console selection succeeded");
            helper.assertTrue(said.stream().anyMatch(line -> line.startsWith("mcacrime.target.needs_explicit")),
                    "the console was not asked to name a target: " + said);
            said.clear();
            helper.assertTrue(execute(console, "crime job assign fence " + named.getStringUUID()) == 1,
                    "the explicit console assignment failed: " + said);
            helper.assertTrue("fence".equals(job(helper, named)), "the named villager is " + job(helper, named));
            reset(helper, named);
            helper.succeed();
        });
    }

    @GameTest(template = "arena", timeoutTicks = 100, batch = "usability_targeting")
    public static void mugAndReportTargetTheAimedVillagerWithinFourBlocks(GameTestHelper helper) {
        List<String> heard = new ArrayList<>();
        ServerPlayer player = player(helper, new Vec3(3.5D, 2.0D, 7.5D), -90.0F, heard);
        Mob victim = villager(helper, 6.5D, 7.5D);
        Mob guard = villager(helper, 5.5D, 9.5D);
        helper.runAfterDelay(2, () -> {
            helper.assertTrue(McaCompat.makeGuard(guard) && McaCompat.isGuard(guard), "fixture guard");
            player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.IRON_SWORD));
            aim(player, victim);
            helper.assertTrue(run(player, 0, "crime mug") == 1, "mug did not start: " + heard);
            helper.assertTrue(ActionSessionManager.forActor(player.getUUID()).isPresent(), "no mug session");
            ActionSessionManager.clearFor(player.getUUID(), CancelReason.ACTOR_GONE);
            heard.clear();
            player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
            aim(player, guard);
            helper.assertTrue(run(player, 0, "crime report") == 1, "the report menu did not open: " + heard);
            heard.clear();
            lookUp(player);
            helper.assertTrue(run(player, 0, "crime report") == 0 && heard.contains("mcacrime.target.none.responder"),
                    "nothing aimed said " + heard);
            helper.succeed();
        });
    }

    // --- keys ----------------------------------------------------------------------------------------

    private static ItemStack craft(GameTestHelper helper, ItemStack... stacks) {
        var input = net.minecraft.world.item.crafting.CraftingInput.of(3, 3, padded(stacks));
        return helper.getLevel().getRecipeManager()
                .getRecipeFor(net.minecraft.world.item.crafting.RecipeType.CRAFTING, input, helper.getLevel())
                .map(holder -> holder.value().assemble(input, helper.getLevel().registryAccess()))
                .orElse(ItemStack.EMPTY);
    }

    private static net.minecraft.core.NonNullList<ItemStack> remainders(GameTestHelper helper, ItemStack... stacks) {
        var input = net.minecraft.world.item.crafting.CraftingInput.of(3, 3, padded(stacks));
        return helper.getLevel().getRecipeManager()
                .getRecipeFor(net.minecraft.world.item.crafting.RecipeType.CRAFTING, input, helper.getLevel())
                .map(holder -> holder.value().getRemainingItems(input))
                .orElseThrow();
    }

    private static List<ItemStack> padded(ItemStack... stacks) {
        List<ItemStack> grid = new ArrayList<>();
        for (int i = 0; i < 9; i++) {
            grid.add(i < stacks.length ? stacks[i] : ItemStack.EMPTY);
        }
        return grid;
    }

    /** A safe placed and a blank key bound to it by its owner, as a player does. */
    private static ItemStack boundKey(GameTestHelper helper, ServerPlayer owner, BlockPos relativeSafe) {
        helper.setBlock(relativeSafe, dev.otectus.mcacrime.block.CrimeBlocks.SAFE.get().defaultBlockState());
        ItemStack key = new ItemStack(CrimeItems.KEY.get());
        use(owner, helper, relativeSafe, Direction.UP, key);
        helper.assertTrue(dev.otectus.mcacrime.locks.KeyBinding.read(key).isPresent(), "the blank key was not bound");
        return key;
    }

    @GameTest(template = "arena", timeoutTicks = 100, batch = "usability_keys")
    public static void bothImpressionsAndFourCastsWorkThroughTheRecipeManager(GameTestHelper helper) {
        List<String> heard = new ArrayList<>();
        ServerPlayer owner = player(helper, new Vec3(3.5D, 2.0D, 3.5D), 0.0F, heard);
        ItemStack key = boundKey(helper, owner, new BlockPos(3, 2, 5));
        var binding = dev.otectus.mcacrime.locks.KeyBinding.read(key).orElseThrow();
        ItemStack clay = craft(helper, key.copy(), new ItemStack(Items.CLAY_BALL));
        helper.assertTrue(dev.otectus.mcacrime.item.lock.KeyMoldItem.recorded(clay).map(binding::equals).orElse(false),
                "the clay impression lost the binding");
        helper.assertTrue(remainders(helper, key.copy(), new ItemStack(Items.CLAY_BALL)).get(0).is(CrimeItems.KEY.get()),
                "the key did not come back");
        ItemStack imprinted = craft(helper, new ItemStack(CrimeItems.KEY_MOLD.get()), key.copy());
        helper.assertTrue(dev.otectus.mcacrime.item.lock.KeyMoldItem.recorded(imprinted).map(binding::equals).orElse(false),
                "the blank-mold impression lost the binding");
        helper.assertTrue(craft(helper, imprinted.copy(), key.copy()).isEmpty(), "a recorded mold was overwritten");
        ItemStack mold = dev.otectus.mcacrime.item.lock.BakedKeyMoldItem.fromRawMold(clay);
        int casts = 0;
        while (!mold.isEmpty()) {
            ItemStack copy = craft(helper, mold.copy(), new ItemStack(Items.IRON_INGOT));
            helper.assertTrue(dev.otectus.mcacrime.locks.KeyBinding.read(copy).map(binding::equals).orElse(false),
                    "cast " + (casts + 1) + " lost the binding");
            mold = remainders(helper, mold.copy(), new ItemStack(Items.IRON_INGOT)).get(0);
            casts++;
            helper.assertTrue(casts <= 4, "a fifth cast was possible");
        }
        helper.assertTrue(casts == 4, "the mold cast " + casts + " keys");
        helper.succeed();
    }

    @GameTest(template = "arena", timeoutTicks = 100, batch = "usability_keys")
    public static void aRenamedKeyKeepsItsNameThroughARingAndAFullRingSaysSo(GameTestHelper helper) {
        List<String> heard = new ArrayList<>();
        ServerPlayer owner = player(helper, new Vec3(3.5D, 2.0D, 3.5D), 0.0F, heard);
        ItemStack a = boundKey(helper, owner, new BlockPos(3, 2, 5));
        ItemStack b = boundKey(helper, owner, new BlockPos(5, 2, 5));
        a.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME, Component.literal("Gate key"));
        ItemStack ring = craft(helper, a.copy(), b.copy());
        helper.assertTrue(dev.otectus.mcacrime.locks.KeyRingBindings.count(ring) == 2, "two keys made " + ring);
        ItemStack last = craft(helper, ring.copy());
        ItemStack ringOfOne = remainders(helper, ring.copy()).get(0);
        ItemStack gate = craft(helper, ringOfOne.copy());
        helper.assertTrue(gate.getHoverName().getString().equals("Gate key"),
                "the renamed key came back as " + gate.getHoverName().getString());
        helper.assertTrue(!last.has(net.minecraft.core.component.DataComponents.CUSTOM_NAME),
                "an unnamed key came back with a name");
        var capacity = dev.otectus.mcacrime.McaCrimeConfig.COMMON.maxKeysPerRing;
        int before = capacity.get();
        try {
            capacity.set(2);
            heard.clear();
            helper.setBlock(new BlockPos(7, 2, 5), dev.otectus.mcacrime.block.CrimeBlocks.SAFE.get().defaultBlockState());
            use(owner, helper, new BlockPos(7, 2, 5), Direction.UP, ring);
            helper.assertTrue(dev.otectus.mcacrime.locks.KeyRingBindings.count(ring) == 2, "a full ring took a key");
            helper.assertTrue(heard.contains("mcacrime.lock.ring_full"), "the full ring said " + heard);
        } finally {
            capacity.set(before);
        }
        heard.clear();
        ItemStack second = new ItemStack(CrimeItems.KEY.get());
        use(owner, helper, new BlockPos(3, 2, 5), Direction.UP, second);
        helper.assertTrue(dev.otectus.mcacrime.locks.KeyBinding.read(second).isEmpty(), "a second first key was issued");
        helper.assertTrue(heard.contains("mcacrime.lock.first_key_issued"), "the second blank key said " + heard);
        helper.succeed();
    }

    /**
     * A post-rekey initialization swaps the ring's stale key for the new binding and keeps the name the
     * key went on with. Forge's {@code KeyWorkflowTest} case of the same name, on a real ring stack: the
     * bindings and the names are two data components here, and the names are keyed by lock.
     */
    @GameTest(template = "arena", timeoutTicks = 20, batch = "usability_keys")
    public static void replacingAStaleKeyOnARingAfterARekeyKeepsItsName(GameTestHelper helper) {
        ResourceLocation dimension = helper.getLevel().dimension().location();
        dev.otectus.mcacrime.locks.LockRecord lock = dev.otectus.mcacrime.locks.LockRecord.of(UUID.randomUUID(),
                dev.otectus.mcacrime.locks.LockTarget.block(dimension, helper.absolutePos(new BlockPos(3, 2, 5))), null);
        dev.otectus.mcacrime.locks.LockRecord safe = dev.otectus.mcacrime.locks.LockRecord.of(UUID.randomUUID(),
                dev.otectus.mcacrime.locks.LockTarget.block(dimension, helper.absolutePos(new BlockPos(5, 2, 5))), null);
        Component name = Component.literal("Front door key");
        ItemStack ring = new ItemStack(CrimeItems.KEY_RING.get());
        dev.otectus.mcacrime.locks.KeyRingBindings.write(ring, List.of(
                dev.otectus.mcacrime.locks.KeyBinding.forLock(lock, "Cell Door"),
                dev.otectus.mcacrime.locks.KeyBinding.forLock(safe, "Safe")));
        dev.otectus.mcacrime.locks.KeyRingBindings.writeLabels(ring, java.util.Map.of(lock.lockId(), name));
        helper.assertTrue(dev.otectus.mcacrime.locks.KeyRingBindings.replace(ring,
                dev.otectus.mcacrime.locks.KeyBinding.forLock(lock.rekeyed(), "Cell Door")), "the stale key was not replaced");
        long revision = dev.otectus.mcacrime.locks.KeyRingBindings.find(ring, lock.lockId()).orElseThrow().bindingRevision();
        helper.assertTrue(revision == lock.rekeyed().bindingRevision(), "the ring still holds revision " + revision);
        helper.assertTrue(name.equals(dev.otectus.mcacrime.locks.KeyRingBindings.labels(ring).get(lock.lockId())),
                "the replaced key lost its name: " + dev.otectus.mcacrime.locks.KeyRingBindings.labels(ring));
        helper.assertTrue(dev.otectus.mcacrime.locks.KeyRingBindings.count(ring) == 2, "the ring changed size");
        helper.succeed();
    }

    @GameTest(template = "arena", timeoutTicks = 400, batch = "usability_keys")
    public static void aRealFurnaceFiresTheMoldKeepingItsBinding(GameTestHelper helper) {
        List<String> heard = new ArrayList<>();
        ServerPlayer owner = player(helper, new Vec3(3.5D, 2.0D, 3.5D), 0.0F, heard);
        ItemStack key = boundKey(helper, owner, new BlockPos(3, 2, 5));
        var binding = dev.otectus.mcacrime.locks.KeyBinding.read(key).orElseThrow();
        ItemStack raw = craft(helper, key.copy(), new ItemStack(Items.CLAY_BALL));
        BlockPos furnacePos = new BlockPos(9, 2, 9);
        helper.setBlock(furnacePos, Blocks.FURNACE.defaultBlockState());
        var furnace = (net.minecraft.world.level.block.entity.FurnaceBlockEntity) helper.getBlockEntity(furnacePos);
        furnace.setItem(0, raw);
        furnace.setItem(1, new ItemStack(Items.COAL));
        helper.succeedWhen(() -> {
            ItemStack baked = furnace.getItem(2);
            helper.assertTrue(baked.is(CrimeItems.BAKED_KEY_MOLD.get()), "the furnace has not fired the mold");
            helper.assertTrue(dev.otectus.mcacrime.item.lock.KeyMoldItem.recorded(baked).map(binding::equals).orElse(false),
                    "the binding was lost in the furnace");
            helper.assertTrue(dev.otectus.mcacrime.item.lock.BakedKeyMoldItem.quality(baked)
                    == dev.otectus.mcacrime.item.lock.BakedKeyMoldItem.STARTING_QUALITY, "the casts were not stamped");
        });
    }

    // --- guillotine ----------------------------------------------------------------------------------

    private static void use(ServerPlayer player, GameTestHelper helper, BlockPos relative, Direction face, ItemStack held) {
        BlockPos abs = helper.absolutePos(relative);
        player.setItemInHand(InteractionHand.MAIN_HAND, held);
        player.gameMode.useItemOn(player, helper.getLevel(), player.getItemInHand(InteractionHand.MAIN_HAND),
                InteractionHand.MAIN_HAND, new BlockHitResult(Vec3.atCenterOf(abs).relative(face, 0.5D), face, abs, false));
    }

    /** Builds the device from its items, as a player does, and returns the pillory's absolute lower half. */
    private static BlockPos device(GameTestHelper helper, ServerPlayer builder, BlockPos floorRelative) {
        use(builder, helper, floorRelative, Direction.UP, new ItemStack(CrimeItems.PILLORY.get()));
        use(builder, helper, floorRelative.above(2), Direction.UP, new ItemStack(CrimeItems.GUILLOTINE.get()));
        return helper.absolutePos(floorRelative.above());
    }

    private static LivingEntity occupant(GameTestHelper helper, BlockPos lower) {
        BlockState state = helper.getLevel().getBlockState(lower);
        Vec3 spot = PilloryBlock.standingPosition(state.getValue(PilloryBlock.FACING), lower);
        EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.getOptional(
                ResourceLocation.fromNamespaceAndPath("mca", "male_villager")).orElseThrow();
        Mob mob = (Mob) type.create(helper.getLevel());
        mob.setNoAi(true);
        if (mob instanceof net.minecraft.world.entity.AgeableMob ageable) {
            ageable.setAge(0);
        }
        mob.moveTo(spot.x, spot.y, spot.z);
        helper.getLevel().addFreshEntity(mob);
        return mob;
    }

    private static void condemn(GameTestHelper helper, CrimeWorldData data, LivingEntity subject) {
        ServerLevel level = helper.getLevel();
        CapitalSentenceService.install(); // the legal answer to "is this subject condemned?", as in production
        UUID id = subject.getUUID();
        helper.assertTrue(CustodyService.captureLawful(data, id, false, CustodyOwner.guard(GUARD), level.getGameTime(),
                BlockPos.ZERO, level.dimension().location()).ok(), "lawful custody");
        CrimeRecord killing = new CrimeRecord(UUID.randomUUID(), id, null, CrimeIds.KILL_GUARD, OptionalInt.empty(),
                true, level.getGameTime(), 60L, -80L, 0L, 1200L, Resolution.UNRESOLVED);
        data.addRecord(killing);
        UUID sentence = UUID.randomUUID();
        helper.assertTrue(SentenceAssignmentService.assign(data, id, sentence, List.of(killing.id()), level.getGameTime()),
                "sentence binding");
        CapitalSentenceService.mark(data, id, sentence, false);
        helper.assertTrue(CapitalSentenceService.condemned(level.getServer(), id), "not condemned");
    }

    private static void tidy(GameTestHelper helper, CrimeWorldData data, LivingEntity subject) {
        DetentionService.releaseSubject(helper.getLevel().getServer(), data, subject.getUUID(),
                DetentionService.ReleaseReason.ADMINISTRATIVE);
        if (data.getCustody(subject.getUUID()) != null) {
            CustodyService.release(helper.getLevel().getServer(), subject.getUUID(),
                    dev.otectus.mcacrime.captivity.CustodyReleaseReason.ADMIN);
        }
        // This subject's order only: the guillotine tests share a batch and run in the same ticks, and
        // clearing every order here would void another test's blade mid-fall.
        ExecutionAuthorization.clear(subject.getUUID(), ExecutionAuthorization.ClearReason.EXPIRED);
    }

    @GameTest(template = "arena", timeoutTicks = 100, batch = "usability_guillotine")
    public static void theDeviceIsBuiltFromItsItemsAndARefusedFrameSaysWhere(GameTestHelper helper) {
        List<String> heard = new ArrayList<>();
        ServerPlayer builder = player(helper, new Vec3(7.5D, 2.0D, 10.5D), 180.0F, heard);
        BlockPos lower = device(helper, builder, new BlockPos(7, 1, 7));
        helper.assertTrue(helper.getLevel().getBlockState(lower.above(2)).getBlock() instanceof GuillotineBlock,
                "the frame did not cap the pillory: " + heard);
        helper.assertTrue(helper.getLevel().getBlockState(lower.above(2)).getValue(GuillotineBlock.FACING)
                == helper.getLevel().getBlockState(lower).getValue(PilloryBlock.FACING), "the frame's facing");
        heard.clear();
        use(builder, helper, new BlockPos(3, 1, 3), Direction.UP, new ItemStack(CrimeItems.GUILLOTINE.get()));
        helper.assertTrue(heard.contains("mcacrime.device.guillotine.place_on_pillory"), "a bare frame said " + heard);
        heard.clear();
        use(builder, helper, new BlockPos(7, 2, 7), Direction.NORTH, ItemStack.EMPTY);
        helper.assertTrue(heard.contains("mcacrime.device.pillory.empty"), "the empty pillory said " + heard);
        helper.succeed();
    }

    @GameTest(template = "arena", timeoutTicks = 200, batch = "usability_guillotine")
    public static void aStaleFallCannotBorrowANewerOrder(GameTestHelper helper) {
        CrimeWorldData data = CrimeWorldData.get(helper.getLevel().getServer());
        List<String> heard = new ArrayList<>();
        ServerPlayer executioner = player(helper, new Vec3(7.5D, 2.0D, 10.5D), 180.0F, heard);
        BlockPos lower = device(helper, executioner, new BlockPos(7, 1, 7));
        LivingEntity condemned = occupant(helper, lower);
        helper.runAfterDelay(2, () -> {
            use(executioner, helper, new BlockPos(7, 3, 7), Direction.NORTH, ItemStack.EMPTY);
            helper.assertTrue(DetentionService.forSubject(data, condemned.getUUID()).isPresent(), "not secured: " + heard);
            condemn(helper, data, condemned);
            use(executioner, helper, new BlockPos(7, 4, 7), Direction.UP, ItemStack.EMPTY);
            helper.assertTrue(heard.contains("mcacrime.device.guillotine.armed"), "said " + heard);
            ExecutionAuthorization.clearAt(helper.getLevel().dimension().location(), lower,
                    ExecutionAuthorization.ClearReason.CHUNK_UNLOADED);
            DetentionRecord occupancy = DetentionService.forSubject(data, condemned.getUUID()).orElseThrow();
            helper.assertTrue(ExecutionAuthorization.arm(helper.getLevel().getServer(), condemned.getUUID(),
                    executioner.getUUID(), true, false, helper.getLevel().dimension().location(), lower,
                    helper.getLevel().getGameTime(), occupancy.occupantGeneration(), occupancy.id()).isPresent(),
                    "the newer order was not armed");
        });
        helper.runAfterDelay(30, () -> {
            GuillotineBlockEntity blade = (GuillotineBlockEntity) helper.getLevel().getBlockEntity(lower.above(2));
            helper.assertTrue(blade != null && blade.completed(), "the blade never landed");
            helper.assertTrue(condemned.isAlive(), "the stale fall executed the subject under a newer order");
            tidy(helper, data, condemned);
            helper.succeed();
        });
    }

    @GameTest(template = "arena", timeoutTicks = 200, batch = "usability_guillotine")
    public static void aBladeLongerThanTheWindowStillStrikesInsideItsOrder(GameTestHelper helper) {
        CrimeWorldData data = CrimeWorldData.get(helper.getLevel().getServer());
        List<String> heard = new ArrayList<>();
        ServerPlayer executioner = player(helper, new Vec3(7.5D, 2.0D, 10.5D), 180.0F, heard);
        BlockPos lower = device(helper, executioner, new BlockPos(7, 1, 7));
        LivingEntity condemned = occupant(helper, lower);
        var window = dev.otectus.mcacrime.McaCrimeConfig.COMMON.executionDelayTicks;
        var blade = dev.otectus.mcacrime.McaCrimeConfig.COMMON.guillotineActivationDelayTicks;
        int windowBefore = window.get();
        int bladeBefore = blade.get();
        helper.runAfterDelay(2, () -> {
            use(executioner, helper, new BlockPos(7, 3, 7), Direction.NORTH, ItemStack.EMPTY);
            condemn(helper, data, condemned);
            window.set(2);
            blade.set(20);
            use(executioner, helper, new BlockPos(7, 4, 7), Direction.UP, ItemStack.EMPTY);
            window.set(windowBefore);
            blade.set(bladeBefore);
            helper.assertTrue(heard.contains("mcacrime.device.guillotine.armed"), "said " + heard);
        });
        helper.runAfterDelay(40, () -> {
            helper.assertTrue(!condemned.isAlive(), "a blade longer than the window let the order expire mid-fall");
            tidy(helper, data, condemned);
            helper.succeed();
        });
    }

    @GameTest(template = "arena", timeoutTicks = 100, batch = "usability_guillotine")
    public static void breakingTheLowerHalfTakesTheFrameAndKeepsTheSentence(GameTestHelper helper) {
        CrimeWorldData data = CrimeWorldData.get(helper.getLevel().getServer());
        List<String> heard = new ArrayList<>();
        ServerPlayer executioner = player(helper, new Vec3(7.5D, 2.0D, 10.5D), 180.0F, heard);
        BlockPos lower = device(helper, executioner, new BlockPos(7, 1, 7));
        LivingEntity condemned = occupant(helper, lower);
        helper.runAfterDelay(2, () -> {
            use(executioner, helper, new BlockPos(7, 3, 7), Direction.NORTH, ItemStack.EMPTY);
            condemn(helper, data, condemned);
            use(executioner, helper, new BlockPos(7, 4, 7), Direction.UP, ItemStack.EMPTY);
            helper.getLevel().destroyBlock(lower, false);
            helper.assertTrue(!(helper.getLevel().getBlockState(lower.above()).getBlock() instanceof PilloryBlock),
                    "the upper half floated");
            helper.assertTrue(!(helper.getLevel().getBlockState(lower.above(2)).getBlock() instanceof GuillotineBlock),
                    "the frame survived");
            helper.assertTrue(DetentionService.forSubject(data, condemned.getUUID()).isEmpty(), "still detained");
            helper.assertTrue(ExecutionAuthorization.pending(condemned.getUUID(), helper.getLevel().getGameTime()).isEmpty(),
                    "the order survived");
            helper.assertTrue(CapitalSentenceService.condemned(helper.getLevel().getServer(), condemned.getUUID()),
                    "the sentence was erased");
            helper.assertTrue(condemned.isAlive(), "the subject died");
            tidy(helper, data, condemned);
            helper.succeed();
        });
    }

    /**
     * Section 7's recipe discovery: picking up an ingredient unlocks the fixed recipes that use it in the
     * vanilla recipe book, through the shipped unlock advancements, and a dynamic key recipe stays out of it.
     */
    @GameTest(template = "arena", timeoutTicks = 100, batch = "usability_keys")
    public static void pickingUpAnIronNuggetUnlocksTheKeyPadlockAndLockpickRecipes(GameTestHelper helper) {
        ServerPlayer learner = player(helper, new Vec3(7.5D, 2.0D, 7.5D), 0.0F, new ArrayList<>());
        helper.assertTrue(!learner.getRecipeBook().contains(ResourceLocation.fromNamespaceAndPath("mcacrime", "key")),
                "the key recipe was known before the nugget");
        ItemStack nugget = new ItemStack(Items.IRON_NUGGET);
        learner.getInventory().add(nugget.copy());
        net.minecraft.advancements.CriteriaTriggers.INVENTORY_CHANGED.trigger(learner, learner.getInventory(), nugget);
        for (String recipe : List.of("key", "padlock", "lockpick")) {
            helper.assertTrue(learner.getRecipeBook().contains(ResourceLocation.fromNamespaceAndPath("mcacrime", recipe)),
                    "an iron nugget did not unlock mcacrime:" + recipe);
        }
        helper.assertTrue(!learner.getRecipeBook().contains(ResourceLocation.fromNamespaceAndPath("mcacrime", "key_mold_copy")),
                "a dynamic key recipe was put in the recipe book");
        helper.succeed();
    }
}
