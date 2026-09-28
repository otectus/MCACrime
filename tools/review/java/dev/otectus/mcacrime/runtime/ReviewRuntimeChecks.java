package dev.otectus.mcacrime.runtime;

import com.mojang.authlib.GameProfile;
import dev.otectus.mcacrime.McaCrimeConfig;
import dev.otectus.mcacrime.block.*;
import dev.otectus.mcacrime.captivity.*;
import dev.otectus.mcacrime.detention.*;
import dev.otectus.mcacrime.enchantment.*;
import dev.otectus.mcacrime.entity.PadlockEntity;
import dev.otectus.mcacrime.item.CrimeItems;
import dev.otectus.mcacrime.locks.*;
import dev.otectus.mcacrime.network.CrimeNetwork;
import dev.otectus.mcacrime.restraint.*;
import dev.otectus.mcacrime.state.*;
import dev.otectus.mcacrime.state.world.CrimeWorldData;
import dev.otectus.mcacrime.tether.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.Connection;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.game.ClientboundCustomPayloadPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.server.players.PlayerList;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.vehicle.Boat;
import net.minecraft.world.entity.vehicle.Minecart;
import net.minecraft.world.item.*;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.living.LivingDamageEvent;
import net.minecraftforge.event.entity.player.AttackEntityEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.fml.common.Mod;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/** Exercises production services and Forge dispatch with real registries in a disposable world. */
@Mod("crime_review_checks")
public final class ReviewRuntimeChecks {
    private final List<String> results = new ArrayList<>();
    private MinecraftServer server;
    private ServerLevel level;
    private CrimeWorldData data;
    private int ticks;
    private int fixture;

    public ReviewRuntimeChecks() {
        MinecraftForge.EVENT_BUS.addListener(this::started);
        MinecraftForge.EVENT_BUS.addListener(this::tick);
    }

    private void started(ServerStartedEvent event) { server = event.getServer(); }

    private void tick(TickEvent.ServerTickEvent event) {
        if (server == null || event.phase != TickEvent.Phase.END || ++ticks != 5) return;
        level = server.overworld();
        data = CrimeWorldData.get(server);
        try {
            runChecks();
        } catch (Throwable failure) {
            results.add("FAIL harness: " + failure);
            failure.printStackTrace();
        } finally {
            results.add("COMPLETE " + results.size() + " checks");
            try { Files.write(Path.of("runtime-results.txt"), results); }
            catch (Exception failure) { throw new RuntimeException(failure); }
            results.forEach(System.out::println);
            server.halt(false);
        }
    }

    private void runChecks() {
        check("padlock owner initializes exactly one working key", this::padlockKey);
        check("chest joins preserve opening and automation protection", this::chestJoins);
        check("detained player cannot attack or break device", this::detentionActions);
        check("cuffed actor cannot apply cuffs through HIGH event router", this::cuffedActor);
        check("rejected Imbue transfers do not discount captor damage", () -> imbue(false));
        check("cuffed captor can passively transfer accepted Imbue damage", () -> imbue(true));
        check("Forge-canceled Imbue damage cannot grant damage reduction", this::imbueCanceled);
        check("escort takeover restores suspended chain", this::escortTakeover);
        check("boat and minecart seating passes entity interaction dispatch", this::vehicleSeating);
        check("cuffed actor cannot attach chains before restriction handler", this::cuffedChainActor);
        check("struggle slot changes retain accepted-input interval", this::struggleSwitch);
        check("bunk sleep wake reuse and respawn hooks", this::bunkSleep);
        check("bunk refused or disabled spawn changes preserve home", this::bunkRefusal);
        check("custody release restores home after repeated bunk sleep", () -> bunkRelease(false));
        check("offline release restores persisted bunk snapshot on login", () -> bunkRelease(true));
        check("bunk release preserves a newer chosen home", this::bunkNewHome);
        check("bunk release leaves another dimension's chosen spawn intact", this::bunkNewDimension);
    }

    private void padlockKey() {
        TestPlayer owner = player();
        BlockPos chest = owner.blockPosition().east(2);
        level.setBlockAndUpdate(chest, Blocks.CHEST.defaultBlockState());
        owner.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(CrimeItems.PADLOCK.get()));
        BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(chest).add(0, 0, -.5), Direction.NORTH, chest, false);
        CrimeItems.PADLOCK.get().useOn(new UseOnContext(owner, InteractionHand.MAIN_HAND, hit));
        LockRecord lock = LockProtection.lockAt(level, chest).orElseThrow();
        PadlockEntity holder = level.getEntitiesOfClass(PadlockEntity.class,
                new net.minecraft.world.phys.AABB(chest).inflate(2)).stream().findFirst().orElseThrow();
        TestPlayer stranger = player();
        ItemStack stolenBlank = new ItemStack(CrimeItems.KEY.get());
        LockInteractions.interact(stranger, level, stolenBlank, holder, chest);
        require(KeyBinding.read(stolenBlank.getTag()).isEmpty(), "stranger initialized owner's lock");
        ItemStack key = new ItemStack(CrimeItems.KEY.get());
        LockInteractions.interact(owner, level, key, holder, chest);
        require(KeyBinding.read(key.getTag()).orElseThrow().opens(data.lock(lock.lockId())), "owner key not bound");
        ItemStack secondBlank = new ItemStack(CrimeItems.KEY.get());
        LockInteractions.interact(owner, level, secondBlank, holder, chest);
        require(KeyBinding.read(secondBlank.getTag()).isEmpty(), "initialized lock copied another blank key");
        LockInteractions.interact(owner, level, key, holder, chest);
        require(!data.lock(lock.lockId()).locked(), "matching key did not unlock");
    }

    private void chestJoins() {
        for (int side : new int[]{-1, 1}) {
            TestPlayer actor = player();
            BlockPos first = actor.blockPosition().east(2);
            level.setBlockAndUpdate(first, Blocks.CHEST.defaultBlockState());
            LockRecord original = LockService.create(data,
                    LockTargetNormalizer.forBlock(level, level.dimension().location(), first), actor.getUUID()).orElseThrow();
            BlockPos other = first.offset(side, 0, 0);
            actor.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.CHEST));
            Items.CHEST.useOn(new UseOnContext(actor, InteractionHand.MAIN_HAND,
                    new BlockHitResult(Vec3.atCenterOf(other.below()).add(0, .5, 0), Direction.UP, other.below(), false)));
            require(level.getBlockState(other).is(Blocks.CHEST), "second chest not placed");
            require(LockTargetNormalizer.partner(level, first) != null, "fixture did not create a double chest");
            for (BlockPos member : List.of(first, other)) {
                require(LockProtection.lockAt(level, member).orElseThrow().lockId().equals(original.lockId()), "lock identity lost");
                require(LockProtection.blocksAutomation(level, member, LockAutomationPolicy.Operation.EXTRACT), "hopper extraction allowed");
                TestPlayer stranger = player();
                PlayerInteractEvent.RightClickBlock click = new PlayerInteractEvent.RightClickBlock(stranger,
                        InteractionHand.MAIN_HAND, member,
                        new BlockHitResult(Vec3.atCenterOf(member), Direction.UP, member, false));
                MinecraftForge.EVENT_BUS.post(click);
                require(click.isCanceled(), "locked chest click not canceled");
            }
        }
    }

    private void detentionActions() {
        TestPlayer captive = player();
        BlockPos pos = captive.blockPosition();
        level.setBlockAndUpdate(pos, CrimeBlocks.PILLORY.get().defaultBlockState());
        require(DetentionService.claim(data, captive.getUUID(), true, DetentionKind.PILLORY,
                level.dimension().location(), pos, "pillory") == DetentionService.Refusal.NONE, "claim refused");
        AttackEntityEvent attack = new AttackEntityEvent(captive, player());
        MinecraftForge.EVENT_BUS.post(attack);
        require(attack.isCanceled(), "detained player attacked");
        BlockEvent.BreakEvent breaking = new BlockEvent.BreakEvent(level, pos, level.getBlockState(pos), captive);
        MinecraftForge.EVENT_BUS.post(breaking);
        require(breaking.isCanceled(), "detained player mined device");
    }

    private void cuffedActor() {
        TestPlayer actor = player();
        TestPlayer target = player();
        target.setPos(actor.getX() + 1, actor.getY(), actor.getZ());
        wear(actor, RestraintSlot.ARMS, RestraintDefinitions.HANDCUFFS_ARMS, ItemStack.EMPTY, actor.getUUID());
        wear(target, RestraintSlot.LEGS, RestraintDefinitions.HANDCUFFS_LEGS, ItemStack.EMPTY, actor.getUUID());
        actor.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(CrimeItems.RESTRAINT_CUFFS.get()));
        PlayerInteractEvent.EntityInteractSpecific click = new PlayerInteractEvent.EntityInteractSpecific(
                actor, InteractionHand.MAIN_HAND, target, new Vec3(0, target.getBbHeight() * .5, 0));
        MinecraftForge.EVENT_BUS.post(click);
        require(!RestraintService.state(target).occupied(RestraintSlot.ARMS), "cuffed actor applied cuffs");
        require(actor.getMainHandItem().getCount() == 1, "refused action consumed cuffs");
        data.removePhysicalRestraint(actor.getUUID());
        MinecraftForge.EVENT_BUS.post(new PlayerInteractEvent.EntityInteractSpecific(actor,
                InteractionHand.MAIN_HAND, target, new Vec3(0, target.getBbHeight() * .5, 0)));
        require(RestraintService.state(target).occupied(RestraintSlot.ARMS),
                "positive control could not apply cuffs: " + actor.lastMessage + "; "
                        + RestraintService.evaluate(actor, target, actor.getMainHandItem(), RestraintSlot.ARMS));
    }

    private void imbue(boolean accepted) {
        TestPlayer captor = player();
        TestPlayer recipient = player();
        recipient.invulnerable = !accepted;
        ItemStack gear = new ItemStack(CrimeItems.RESTRAINT_CUFFS.get());
        gear.enchant(CrimeEnchantments.IMBUE.get(), 1);
        wear(recipient, RestraintSlot.ARMS, RestraintDefinitions.HANDCUFFS_ARMS, gear, captor.getUUID());
        wear(captor, RestraintSlot.ARMS, RestraintDefinitions.HANDCUFFS_ARMS, ItemStack.EMPTY, captor.getUUID());
        ImbueIndex.rebuild(data.physicalRestraints());
        LivingDamageEvent event = new LivingDamageEvent(captor, level.damageSources().generic(), 10F);
        float before = recipient.getHealth();
        ImbueHandler.onDamage(event);
        if (accepted) {
            require(recipient.getHealth() < before, "passive transfer canceled by cuffs");
            require(event.getAmount() < 10F, "accepted transfer did not reduce damage");
        } else {
            require(event.getAmount() == 10F, "rejected transfer granted reduction");
            require(recipient.getHealth() == before, "invulnerable recipient damaged");
        }
    }

    private void escortTakeover() {
        TestPlayer subject = player(), holder = player(), guard = player();
        holder.setPos(subject.getX() + 1, subject.getY(), subject.getZ());
        guard.setPos(subject.getX() - 1, subject.getY(), subject.getZ());
        wear(subject, RestraintSlot.ARMS, RestraintDefinitions.HANDCUFFS_ARMS, ItemStack.EMPTY, holder.getUUID());
        TetherRecord chain = TetherService.attach(data, subject, holder, TetherKind.CHAIN, holder.getUUID(), true).orElseThrow();
        require(TetherService.escort(guard, subject).isPresent(), "takeover refused");
        require(data.tether(chain.id()).suspended(), "old chain not suspended");
        TetherService.endEscort(server, subject.getUUID(), TetherService.DetachReason.RELEASED);
        require(TetherService.active(data, subject.getUUID()).orElseThrow().id().equals(chain.id()), "old chain not resumed");
    }

    private void imbueCanceled() {
        TestPlayer captor = player(), recipient = player();
        ItemStack gear = new ItemStack(CrimeItems.RESTRAINT_CUFFS.get());
        gear.enchant(CrimeEnchantments.IMBUE.get(), 1);
        wear(recipient, RestraintSlot.ARMS, RestraintDefinitions.HANDCUFFS_ARMS, gear, captor.getUUID());
        ImbueIndex.rebuild(data.physicalRestraints());
        boolean[] reached = {false};
        java.util.function.Consumer<LivingDamageEvent> cancel = event -> {
            if (event.getEntity() == recipient) {
                reached[0] = true;
                event.setCanceled(true);
            }
        };
        MinecraftForge.EVENT_BUS.addListener(net.minecraftforge.eventbus.api.EventPriority.HIGHEST, cancel);
        try {
            LivingDamageEvent event = new LivingDamageEvent(captor, level.damageSources().generic(), 10F);
            ImbueHandler.onDamage(event);
            require(reached[0], "recipient damage never reached cancellation listener");
            require(event.getAmount() == 10F, "canceled actual damage granted reduction");
            require(recipient.getHealth() == recipient.getMaxHealth(), "canceled recipient damaged");
        } finally { MinecraftForge.EVENT_BUS.unregister(cancel); }
    }

    private void vehicleSeating() {
        for (boolean boat : new boolean[]{true, false}) {
            TestPlayer actor = player(), captive = player();
            captive.setPos(actor.getX() + 1, actor.getY(), actor.getZ());
            wear(captive, RestraintSlot.ARMS, RestraintDefinitions.HANDCUFFS_ARMS, ItemStack.EMPTY, actor.getUUID());
            wear(captive, RestraintSlot.LEGS, RestraintDefinitions.HANDCUFFS_LEGS, ItemStack.EMPTY, actor.getUUID());
            require(TetherService.escort(actor, captive).isPresent(), "escort fixture failed");
            Entity vehicle = boat ? new Boat(level, actor.getX(), actor.getY(), actor.getZ() + 1)
                    : new Minecart(level, actor.getX(), actor.getY(), actor.getZ() + 1);
            level.addFreshEntity(vehicle);
            MinecraftForge.EVENT_BUS.post(new PlayerInteractEvent.EntityInteract(actor, InteractionHand.MAIN_HAND, vehicle));
            require(captive.getVehicle() == vehicle, "captive not seated in " + vehicle.getType());
        }
    }

    private void struggleSwitch() {
        TestPlayer subject = player();
        wear(subject, RestraintSlot.ARMS, RestraintDefinitions.DUCK_TAPE_ARMS, ItemStack.EMPTY, subject.getUUID());
        wear(subject, RestraintSlot.LEGS, RestraintDefinitions.DUCK_TAPE_LEGS, ItemStack.EMPTY, subject.getUUID());
        EscapeService.struggle(subject, 0L, RestraintSlot.ARMS, StruggleInput.LEFT, 1);
        EscapeService.Attempt second = EscapeService.struggle(subject, 0L, RestraintSlot.LEGS, StruggleInput.RIGHT, 2);
        require(second.outcome() == EscapeService.Outcome.REFUSED, "same-tick slot switch accepted");
    }

    private void cuffedChainActor() {
        TestPlayer actor = player(), captive = player();
        captive.setPos(actor.getX() + 1, actor.getY(), actor.getZ());
        wear(captive, RestraintSlot.ARMS, RestraintDefinitions.HANDCUFFS_ARMS, ItemStack.EMPTY, actor.getUUID());
        wear(actor, RestraintSlot.ARMS, RestraintDefinitions.HANDCUFFS_ARMS, ItemStack.EMPTY, actor.getUUID());
        actor.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.CHAIN));
        MinecraftForge.EVENT_BUS.post(new PlayerInteractEvent.EntityInteract(actor, InteractionHand.MAIN_HAND, captive));
        require(TetherService.active(data, captive.getUUID()).isEmpty(), "cuffed actor attached chain");
        require(actor.getMainHandItem().getCount() == 1, "refused chain consumed");
        data.removePhysicalRestraint(actor.getUUID());
        MinecraftForge.EVENT_BUS.post(new PlayerInteractEvent.EntityInteract(actor, InteractionHand.MAIN_HAND, captive));
        require(TetherService.active(data, captive.getUUID()).isPresent(), "positive control could not attach chain");
    }

    private BlockPos bunk(TestPlayer sleeper) {
        BlockPos foot = sleeper.blockPosition().east();
        BlockState state = CrimeBlocks.BUNK.get().defaultBlockState().setValue(BedBlock.FACING, Direction.NORTH);
        level.setBlock(foot, state.setValue(BedBlock.PART, BedPart.FOOT), 2);
        BlockPos head = foot.north();
        level.setBlock(head, state.setValue(BedBlock.PART, BedPart.HEAD), 3);
        return head;
    }

    private void useBunk(TestPlayer sleeper, BlockPos pos) {
        level.getBlockState(pos).use(level, sleeper, InteractionHand.MAIN_HAND,
                new BlockHitResult(Vec3.atCenterOf(pos), Direction.UP, pos, false));
    }

    private void bunkSleep() {
        TestPlayer sleeper = player();
        BlockPos head = bunk(sleeper);
        level.setDayTime(14000);
        level.updateSkyBrightness();
        useBunk(sleeper, head);
        require(sleeper.isSleeping(), "bunk sleep failed: " + sleeper.lastMessage);
        sleeper.stopSleepInBed(true, true);
        require(!level.getBlockState(head).getValue(BedBlock.OCCUPIED), "bunk stuck occupied");
        useBunk(sleeper, head);
        require(sleeper.isSleeping(), "bunk could not be reused");
        sleeper.stopSleepInBed(true, true);
        require(level.getBlockState(head).getRespawnPosition(EntityType.PLAYER, level, head, 0F, sleeper).isPresent(), "bunk has no respawn position");
    }

    private void bunkRefusal() {
        TestPlayer sleeper = player();
        BlockPos home = sleeper.blockPosition().west(10), head = bunk(sleeper);
        sleeper.setRespawnPosition(level.dimension(), home, 90F, true, false);
        level.setDayTime(1000);
        level.updateSkyBrightness();
        useBunk(sleeper, head);
        require(home.equals(sleeper.getRespawnPosition()), "refused sleep overwrote home");
        McaCrimeConfig.COMMON.bunkSetsRespawn.set(false);
        try {
            level.setDayTime(14000);
            level.updateSkyBrightness();
            useBunk(sleeper, head);
            require(home.equals(sleeper.getRespawnPosition()), "disabled bunk respawn overwrote home");
            sleeper.stopSleepInBed(true, true);
        } finally { McaCrimeConfig.COMMON.bunkSetsRespawn.set(true); }
    }

    private void custody(TestPlayer captive, TestPlayer holder) {
        data.putCustody(new CustodyRecord(captive.getUUID(), true, false,
                CustodyOwner.kidnapper(holder.getUUID()), level.getGameTime(), null, null));
        require(data.getCustody(captive.getUUID()) != null, "custody fixture not installed");
    }

    private void sleepAndWake(TestPlayer sleeper, BlockPos head) {
        level.setDayTime(14000);
        level.updateSkyBrightness();
        useBunk(sleeper, head);
        require(sleeper.isSleeping(), "custody bunk refused sleep: " + sleeper.lastMessage);
        sleeper.stopSleepInBed(true, true);
    }

    private void bunkRelease(boolean offline) {
        TestPlayer sleeper = player(), holder = player();
        BlockPos home = sleeper.blockPosition().west(10), head = bunk(sleeper);
        sleeper.setRespawnPosition(level.dimension(), home, 73F, true, false);
        custody(sleeper, holder);
        onlinePlayers().put(sleeper.getUUID(), sleeper);
        sleepAndWake(sleeper, head);
        sleepAndWake(sleeper, head);
        require(home.equals(BunkRespawnPolicy.snapshotFor(sleeper).orElseThrow().pos()),
                "repeated sleep replaced original home with bunk");
        require(head.equals(sleeper.getRespawnPosition()), "bunk did not become temporary spawn");
        if (offline) {
            PlayerCrimeData capability = CrimeCapabilities.get(sleeper).orElseThrow();
            CompoundTag saved = capability.save();
            onlinePlayers().remove(sleeper.getUUID());
            CustodyService.release(server, sleeper.getUUID(), CustodyReleaseReason.ADMIN);
            capability.setBunkRespawnSnapshot(null);
            capability.load(saved);
            require(BunkRespawnPolicy.snapshotFor(sleeper).isPresent(), "saved snapshot missing");
            onlinePlayers().put(sleeper.getUUID(), sleeper);
            BunkRespawnPolicy.onLogin(new PlayerEvent.PlayerLoggedInEvent(sleeper));
        } else {
            CustodyService.release(server, sleeper.getUUID(), CustodyReleaseReason.ADMIN);
        }
        require(home.equals(sleeper.getRespawnPosition()), "release did not restore home");
        require(sleeper.getRespawnAngle() == 73F && sleeper.isRespawnForced(), "home attributes lost");
        require(BunkRespawnPolicy.snapshotFor(sleeper).isEmpty(), "released snapshot not consumed");
    }

    private void bunkNewHome() {
        TestPlayer sleeper = player(), holder = player();
        BlockPos home = sleeper.blockPosition().west(10), newer = home.north(4), head = bunk(sleeper);
        sleeper.setRespawnPosition(level.dimension(), home, 0F, true, false);
        custody(sleeper, holder);
        onlinePlayers().put(sleeper.getUUID(), sleeper);
        sleepAndWake(sleeper, head);
        sleeper.setRespawnPosition(level.dimension(), newer, 20F, true, false);
        // Sleeping in custody again must remember the deliberate new choice, not the old home.
        sleepAndWake(sleeper, head);
        require(newer.equals(BunkRespawnPolicy.snapshotFor(sleeper).orElseThrow().pos()), "new home ignored");
        CustodyService.release(server, sleeper.getUUID(), CustodyReleaseReason.ADMIN);
        require(newer.equals(sleeper.getRespawnPosition()), "release overwrote newer home");
    }

    private void bunkNewDimension() {
        TestPlayer sleeper = player(), holder = player();
        BlockPos head = bunk(sleeper);
        custody(sleeper, holder);
        onlinePlayers().put(sleeper.getUUID(), sleeper);
        sleepAndWake(sleeper, head);
        sleeper.setRespawnPosition(net.minecraft.world.level.Level.NETHER, head, 28F, true, false);
        CustodyService.release(server, sleeper.getUUID(), CustodyReleaseReason.ADMIN);
        require(head.equals(sleeper.getRespawnPosition())
                        && sleeper.getRespawnDimension().equals(net.minecraft.world.level.Level.NETHER),
                "release overwrote deliberately chosen spawn in another dimension");
        require(sleeper.getRespawnAngle() == 28F, "new spawn angle overwritten");
        require(BunkRespawnPolicy.snapshotFor(sleeper).isEmpty(), "obsolete snapshot not consumed");
    }

    @SuppressWarnings("unchecked")
    private Map<UUID, ServerPlayer> onlinePlayers() {
        // Fixtures use server lookup without opening client sockets or changing the online tick list.
        for (java.lang.reflect.Field field : PlayerList.class.getDeclaredFields()) {
            if (!(field.getGenericType() instanceof java.lang.reflect.ParameterizedType type)) continue;
            if (type.getRawType() != Map.class || !Arrays.equals(type.getActualTypeArguments(),
                    new java.lang.reflect.Type[]{UUID.class, ServerPlayer.class})) continue;
            try {
                field.setAccessible(true);
                return (Map<UUID, ServerPlayer>) field.get(server.getPlayerList());
            } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
        }
        throw new AssertionError("server player lookup map not found");
    }

    private TestPlayer player() {
        TestPlayer player = new TestPlayer(level, new GameProfile(UUID.randomUUID(), "Fixture" + fixture));
        BlockPos pos = new BlockPos((fixture++ % 8) * 16, 65, (fixture / 8) * 16);
        for (BlockPos ground : BlockPos.betweenClosed(pos.offset(-5, -1, -5), pos.offset(5, -1, 5))) {
            level.setBlock(ground, Blocks.STONE.defaultBlockState(), 2);
        }
        player.setPos(pos.getX() + .5, pos.getY(), pos.getZ() + .5);
        // New server players have a grace period; these fixtures represent players already playing.
        net.minecraftforge.fml.util.ObfuscationReflectionHelper.setPrivateValue(
                net.minecraft.server.level.ServerPlayer.class, player, 0, "f_8921_");
        level.addNewPlayer(player);
        require(CrimeCapabilities.get(player).isPresent(), "player capability missing");
        return player;
    }

    private void wear(TestPlayer subject, RestraintSlot slot, ResourceLocation definition, ItemStack gear, UUID applier) {
        PhysicalRestraintState state = data.physicalRestraint(subject.getUUID());
        if (state == null) state = PhysicalRestraintState.empty(subject.getUUID(), true, level.dimension().location());
        AppliedRestraint worn = new AppliedRestraint(UUID.randomUUID(), definition,
                gear.isEmpty() ? null : gear.save(new CompoundTag()), 100, 1, RestraintApplier.player(applier),
                AppliedRestraint.ApplicationContext.VOLUNTARY, AppliedRestraint.Provenance.SYSTEM_ISSUED,
                AppliedRestraint.ReturnPolicy.NONE, null, level.getGameTime(), 1);
        require(data.putPhysicalRestraint(state.with(slot, worn)), "fixture state refused");
    }

    private void check(String name, Runnable test) {
        try { test.run(); results.add("PASS " + name); }
        catch (Throwable failure) { results.add("FAIL " + name + ": " + failure); failure.printStackTrace(); }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static final class TestPlayer extends FakePlayer {
        boolean invulnerable;
        String lastMessage = "";
        TestPlayer(ServerLevel level, GameProfile profile) { super(level, profile); }
        @Override public boolean isInvulnerableTo(DamageSource source) { return invulnerable; }
        @Override public boolean canHarmPlayer(net.minecraft.world.entity.player.Player other) { return true; }
        @Override public void displayClientMessage(Component message, boolean actionBar) {
            lastMessage = message.getString();
        }
    }
}
