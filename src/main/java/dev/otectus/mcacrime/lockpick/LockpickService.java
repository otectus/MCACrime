package dev.otectus.mcacrime.lockpick;

import dev.otectus.mcacrime.McaCrimeConfig;
import dev.otectus.mcacrime.audio.CrimeSounds;
import dev.otectus.mcacrime.block.entity.SafeBlockEntity;
import dev.otectus.mcacrime.entity.PadlockEntity;
import dev.otectus.mcacrime.locks.LockHolder;
import dev.otectus.mcacrime.locks.LockInteractions;
import dev.otectus.mcacrime.locks.LockRecord;
import dev.otectus.mcacrime.locks.LockService;
import dev.otectus.mcacrime.network.CrimeNetwork;
import dev.otectus.mcacrime.network.LockpickBeginS2CPacket;
import dev.otectus.mcacrime.network.LockpickPhaseS2CPacket;
import dev.otectus.mcacrime.network.LockpickResultS2CPacket;
import dev.otectus.mcacrime.restraint.AppliedRestraint;
import dev.otectus.mcacrime.restraint.PhysicalRestraintState;
import dev.otectus.mcacrime.restraint.RemovalService;
import dev.otectus.mcacrime.restraint.RestraintDefinition;
import dev.otectus.mcacrime.restraint.RestraintDefinitions;
import dev.otectus.mcacrime.restraint.RestraintService;
import dev.otectus.mcacrime.restraint.RestraintSlot;
import dev.otectus.mcacrime.restraint.Session;
import dev.otectus.mcacrime.restraint.SessionCancelCause;
import dev.otectus.mcacrime.restraint.SessionKind;
import dev.otectus.mcacrime.restraint.SessionRegistry;
import dev.otectus.mcacrime.stat.CrimeStats;
import dev.otectus.mcacrime.state.world.CrimeWorldData;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Containers;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraftforge.registries.ForgeRegistries;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;

/**
 * The server half of native lockpicking: it owns the session, the meter and the outcome (M3.3).
 *
 * <p>Every one of upstream's exploits is a thing this class refuses to accept from a packet. The
 * actor is the connection's player, never a UUID in a message. The target was pinned when the session
 * opened, never named by an arriving attempt. The meter is drained here on the server tick. The
 * outcome is computed here. The pick is damaged here. There is no success packet, because there is
 * nothing a client could say that would make a lock open.
 *
 * <p>What the client does own is aiming: it draws the dial, it decides when the player pressed the
 * button, and it sends an angle. That is unavoidable in an alignment game and is stated plainly in
 * the specification — a timing game cannot prove a human supplied the input. What it can do, and what
 * this does, is make fabricated completion, arbitrary targets and impossible rates all impossible.
 */
public final class LockpickService {

    /** Phase targets. Server-side, and never derived from anything a client sent. */
    private static final Random RANDOM = new Random();

    private LockpickService() {
    }

    // --- configuration ------------------------------------------------------------------------------

    public static boolean enabled() {
        try {
            return McaCrimeConfig.COMMON.enableLockpicking.get();
        } catch (IllegalStateException notLoaded) {
            return true;
        }
    }

    public static int drainDivisor() {
        try {
            return McaCrimeConfig.COMMON.lockpickDrainPerTickDivisor.get();
        } catch (IllegalStateException notLoaded) {
            return 200;
        }
    }

    public static int minAttemptInterval() {
        try {
            return McaCrimeConfig.COMMON.lockpickMinAttemptIntervalTicks.get();
        } catch (IllegalStateException notLoaded) {
            return 2;
        }
    }

    public static double windowBelow() {
        try {
            return McaCrimeConfig.COMMON.lockpickWindowBelowDegrees.get();
        } catch (IllegalStateException notLoaded) {
            return 10.0D;
        }
    }

    public static double windowAbove() {
        try {
            return McaCrimeConfig.COMMON.lockpickWindowAboveDegrees.get();
        } catch (IllegalStateException notLoaded) {
            return 5.0D;
        }
    }

    public static double maxRange() {
        try {
            return McaCrimeConfig.COMMON.lockpickMaxRangeBlocks.get();
        } catch (IllegalStateException notLoaded) {
            return 5.0D;
        }
    }

    public static boolean destructiveOutcome() {
        try {
            return McaCrimeConfig.COMMON.lockpickDestructiveOutcome.get();
        } catch (IllegalStateException notLoaded) {
            return false;
        }
    }

    private static int sessionTimeoutTicks() {
        try {
            return McaCrimeConfig.COMMON.sessionTimeoutTicks.get();
        } catch (IllegalStateException notLoaded) {
            return 200;
        }
    }

    // --- opening a session ---------------------------------------------------------------------------

    /** Opens a session against a lock holder: a cell door, a safe, a padlock. */
    public static InteractionResult begin(@Nullable ServerPlayer player, @Nullable LockHolder holder,
                                          @Nullable LockRecord lock) {
        if (player == null || holder == null || lock == null || !enabled()) {
            return InteractionResult.PASS;
        }
        if (!lock.locked()) {
            return InteractionResult.PASS; // nothing to pick; the ordinary interaction applies
        }
        LockpickTarget target = holder instanceof PadlockEntity padlock
                ? LockpickTarget.padlock(lock.lockId(), lock.bindingRevision(), padlock.getUUID(),
                        lock.target().blockPos().orElse(null))
                : LockpickTarget.block(lock.lockId(), lock.bindingRevision(),
                        lock.target().blockPos().orElse(player.blockPosition()));
        LockpickProfile profile = holder.pickProfile(lock.reinforced());
        return open(player, target, profile.pick(), lock.revision())
                ? InteractionResult.SUCCESS
                : InteractionResult.FAIL;
    }

    /**
     * Opens a session against something somebody is wearing.
     *
     * <p>The instance id is pinned, not just the slot: a pair of cuffs removed and replaced during the
     * pick is a different pair, and the session that was aimed at the first one must not open the
     * second.
     */
    public static boolean beginRestraint(@Nullable ServerPlayer actor, @Nullable LivingEntity subject,
                                         @Nullable RestraintSlot slot) {
        if (actor == null || subject == null || slot == null || !enabled()) {
            return false;
        }
        PhysicalRestraintState state = RestraintService.state(subject);
        AppliedRestraint worn = state == null ? null : state.slot(slot).orElse(null);
        if (worn == null) {
            return false;
        }
        RestraintDefinition definition = worn.definition().orElse(null);
        Optional<LockpickProfile.Pick> pick = LockpickProfile.of(definition);
        if (pick.isEmpty()) {
            actor.displayClientMessage(Component.translatable("mcacrime.lockpick.not_pickable"), true);
            return false;
        }
        return open(actor, LockpickTarget.restraint(subject.getUUID(), slot, worn.instanceId()),
                pick.get(), state.revision());
    }

    /** The common half: build the session, register it, tell the client to open its dial. */
    private static boolean open(ServerPlayer player, LockpickTarget target, LockpickProfile.Pick pick,
                                long targetRevision) {
        ItemStack held = player.getMainHandItem();
        if (!LockInteractions.isLockpick(held)) {
            return false;
        }
        long now = player.level().getGameTime();
        SessionRegistry registry = SessionRegistry.server();
        LockpickSession session = new LockpickSession(registry.allocateId(), player.getUUID(), target,
                targetRevision, player.level().dimension().location(),
                ForgeRegistries.ITEMS.getKey(held.getItem()), now + sessionTimeoutTicks(), pick,
                nextTarget(), now);
        Optional<Session> opened = registry.open(session);
        if (opened.isEmpty()) {
            player.displayClientMessage(Component.translatable("mcacrime.lockpick.busy"), true);
            return false;
        }
        CrimeNetwork.sendLockpickBegin(player, new LockpickBeginS2CPacket(session.id(),
                pick.progressIncrease(), pick.speedIncrease(), session.phase(),
                session.phaseTargetMilliDegrees(), session.meter(), drainDivisor()));
        return true;
    }

    private static int nextTarget() {
        return RANDOM.nextInt(LockpickSession.FULL_TURN_MILLI);
    }

    // --- arriving packets ----------------------------------------------------------------------------

    /**
     * One alignment attempt.
     *
     * <p>Validated in this order: the sender owns this session, the session is live, the session is a
     * lockpick session, the picker still holds a pick, the target is still there and still the same
     * one, and only then is the angle scored. Each of those is a thing upstream does not check.
     */
    public static LockpickSession.AttemptResult attempt(@Nullable ServerPlayer player, long sessionId,
                                                        int phase, int angleMilliDegrees) {
        if (player == null || player.level().isClientSide()) {
            return LockpickSession.AttemptResult.WRONG_PHASE;
        }
        long now = player.level().getGameTime();
        LockpickSession session = session(player, sessionId, now);
        if (session == null) {
            return LockpickSession.AttemptResult.WRONG_PHASE;
        }
        if (!stillValid(player, session)) {
            end(player, session, LockpickOutcome.CANCELLED, SessionCancelCause.TARGET_REMOVED);
            return LockpickSession.AttemptResult.WRONG_PHASE;
        }
        LockpickSession.AttemptResult result = session.attempt(phase,
                LockpickSession.wrap(angleMilliDegrees), now, minAttemptInterval(), windowBelow(),
                windowAbove());
        switch (result) {
            case HIT -> {
                session.setPhaseTarget(nextTarget());
                CrimeSounds.lockpickProgress(player);
                CrimeNetwork.sendLockpickPhase(player, new LockpickPhaseS2CPacket(session.id(),
                        session.phase(), session.phaseTargetMilliDegrees(), session.meter()));
            }
            case WIN -> succeed(player, session);
            case MISS -> CrimeNetwork.sendLockpickPhase(player, new LockpickPhaseS2CPacket(session.id(),
                    session.phase(), session.phaseTargetMilliDegrees(), session.meter()));
            default -> {
                // refused: nothing changes, and the client is told nothing it could learn from
            }
        }
        return result;
    }

    /** The picker asked to stop. */
    public static void cancel(@Nullable ServerPlayer player, long sessionId) {
        if (player == null) {
            return;
        }
        LockpickSession session = session(player, sessionId, player.level().getGameTime());
        if (session != null) {
            end(player, session, LockpickOutcome.CANCELLED, SessionCancelCause.CANCELLED);
        }
    }

    @Nullable
    private static LockpickSession session(ServerPlayer player, long sessionId, long now) {
        return SessionRegistry.server().validate(sessionId, player.getUUID(), now)
                .filter(session -> session.kind() == SessionKind.LOCKPICK)
                .map(LockpickSession.class::cast)
                .orElse(null);
    }

    // --- the tick -------------------------------------------------------------------------------------

    /**
     * Drains every live session, and ends the ones whose world moved on.
     *
     * <p>The drain is the clock of the mini-game, so it has to be a server tick rather than a client
     * frame: on a frame clock, a player with a slow machine has an easier lock than a player with a
     * fast one, which is what upstream ships.
     */
    public static void serverTick(@Nullable MinecraftServer server) {
        if (server == null) {
            return;
        }
        long now = server.overworld().getGameTime();
        List<LockpickSession> live = new ArrayList<>();
        SessionRegistry registry = SessionRegistry.server();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            registry.forActor(player.getUUID())
                    .filter(session -> session.kind() == SessionKind.LOCKPICK)
                    .map(LockpickSession.class::cast)
                    .ifPresent(live::add);
        }
        for (LockpickSession session : live) {
            ServerPlayer player = server.getPlayerList().getPlayer(session.actor());
            if (player == null) {
                registry.cancel(session.id(), SessionCancelCause.LOGOUT);
                continue;
            }
            if (!stillValid(player, session)) {
                end(player, session, LockpickOutcome.CANCELLED, SessionCancelCause.TARGET_REMOVED);
                continue;
            }
            if (session.drain(now, drainDivisor())) {
                fail(player, session);
            }
        }
    }

    /**
     * Whether a session may continue: same world, in range, still holding the pick, target unchanged.
     *
     * <p>The revision comparison is what makes a stale session harmless. A lock replaced at the same
     * coordinates has a different id; a rekeyed lock has a different binding revision; a replaced
     * restraint has a different instance id. None of them can be opened by a session pinned to its
     * predecessor.
     */
    private static boolean stillValid(ServerPlayer player, LockpickSession session) {
        if (!player.isAlive() || player.isSpectator()) {
            return false;
        }
        if (!player.level().dimension().location().equals(session.dimension())) {
            return false;
        }
        if (!LockInteractions.isLockpick(player.getMainHandItem())) {
            return false;
        }
        LockpickTarget target = session.lockTarget();
        CrimeWorldData data = LockService.data(player.getServer());
        return switch (target.kind()) {
            case BLOCK, PADLOCK -> {
                LockRecord lock = data == null ? null : data.lock(target.lockId());
                if (lock == null || !lock.locked()
                        || lock.bindingRevision() != target.bindingRevision()) {
                    yield false;
                }
                BlockPos pos = target.position().orElse(null);
                yield pos != null && player.distanceToSqr(pos.getX() + 0.5D, pos.getY() + 0.5D,
                        pos.getZ() + 0.5D) <= maxRange() * maxRange();
            }
            case RESTRAINT -> {
                Entity subject = subject(player, target.subject());
                if (!(subject instanceof LivingEntity living)
                        || player.distanceToSqr(subject) > maxRange() * maxRange()) {
                    yield false;
                }
                PhysicalRestraintState state = RestraintService.state(living);
                AppliedRestraint worn = state == null ? null
                        : state.slot(target.slot()).orElse(null);
                yield worn != null && worn.instanceId().equals(target.instanceId());
            }
        };
    }

    @Nullable
    private static Entity subject(ServerPlayer player, @Nullable UUID id) {
        if (id == null || !(player.level() instanceof ServerLevel level)) {
            return null;
        }
        return level.getEntity(id);
    }

    // --- outcomes --------------------------------------------------------------------------------------

    /** The meter hit zero. */
    private static void fail(ServerPlayer player, LockpickSession session) {
        end(player, session, LockpickOutcome.FAILED, SessionCancelCause.EXPIRED);
    }

    /** The meter reached forty. The outcome is resolved here, on the server, or not at all. */
    private static void succeed(ServerPlayer player, LockpickSession session) {
        LockpickTarget target = session.lockTarget();
        CrimeWorldData data = LockService.data(player.getServer());
        boolean opened = switch (target.kind()) {
            case RESTRAINT -> openRestraint(player, target);
            case PADLOCK -> openPadlock(player, data, target);
            case BLOCK -> openBlock(player, data, target);
        };
        CrimeStats.award(player, CrimeStats.SUCCESSFUL_LOCKPICKS);
        end(player, session, opened ? LockpickOutcome.SUCCESS : LockpickOutcome.CANCELLED,
                SessionCancelCause.TARGET_REMOVED);
    }

    private static boolean openRestraint(ServerPlayer player, LockpickTarget target) {
        Entity subject = subject(player, target.subject());
        if (!(subject instanceof LivingEntity living)) {
            return false;
        }
        return RemovalService.remove(living, target.slot(), RemovalService.Reason.PICKED, player)
                .removed();
    }

    /** A picked padlock comes off and the block it was protecting is untouched. */
    private static boolean openPadlock(ServerPlayer player, @Nullable CrimeWorldData data,
                                       LockpickTarget target) {
        Entity entity = subject(player, target.entityId());
        if (!(entity instanceof PadlockEntity padlock)) {
            return false;
        }
        padlock.pickedOpen();
        return true;
    }

    /**
     * A picked door or safe.
     *
     * <p>Two outcomes, and the default is the non-destructive one (§6.2). {@code true} reproduces the
     * named Cuffed parity outcome — the block is destroyed — and for a safe the contents are moved out
     * <b>before</b> the removal, exactly once, rather than being left to a loot table that may or may
     * not run.
     */
    private static boolean openBlock(ServerPlayer player, @Nullable CrimeWorldData data,
                                     LockpickTarget target) {
        BlockPos pos = target.position().orElse(null);
        if (pos == null || data == null) {
            return false;
        }
        if (!destructiveOutcome()) {
            boolean unlocked = LockService.setLocked(data, target.lockId(), false).isPresent();
            if (unlocked) {
                CrimeSounds.lockPicked(player.level(), pos);
                notifyHolder(player.level(), pos);
            }
            return unlocked;
        }
        Level level = player.level();
        if (level.getBlockEntity(pos) instanceof SafeBlockEntity safe) {
            Containers.dropContents(level, pos, safe);
            safe.clearContent();
        }
        LockService.detach(data, target.lockId());
        CrimeSounds.lockPicked(level, pos);
        return level.destroyBlock(pos, true, player);
    }

    /** Lets a block entity know its lock changed, when one is there to be told. */
    private static void notifyHolder(Level level, BlockPos pos) {
        if (level.getBlockEntity(pos) instanceof LockHolder holder) {
            holder.onLockChanged();
        }
    }

    /**
     * Ends a session, damages the pick once, and tells the client what happened.
     *
     * <p>One damage per session, whatever the outcome, which is the source's behaviour and the honest
     * one: a pick is consumed by the attempt, not by the result. Breaking is counted separately from
     * using, because those are two different statistics and the specification says so.
     */
    private static void end(ServerPlayer player, LockpickSession session, LockpickOutcome outcome,
                            SessionCancelCause cause) {
        session.finish();
        SessionRegistry.server().cancel(session.id(), cause);
        if (outcome != LockpickOutcome.CANCELLED) {
            damagePick(player);
        }
        CrimeNetwork.sendLockpickResult(player, new LockpickResultS2CPacket(session.id(), outcome));
    }

    /** Spends one point of the held pick, and counts the break separately from the use. */
    private static void damagePick(ServerPlayer player) {
        ItemStack held = player.getMainHandItem();
        if (!LockInteractions.isLockpick(held) || !held.isDamageableItem()) {
            return;
        }
        // Decided before the damage lands, so "used" and "broken" are two answers to one question
        // rather than a callback that may or may not run (§8: a used pick is not a broken pick).
        PickWear.Outcome outcome = PickWear.wear(held.getDamageValue(), held.getMaxDamage());
        held.hurtAndBreak(1, player, broken -> broken.broadcastBreakEvent(InteractionHand.MAIN_HAND));
        if (outcome == PickWear.Outcome.BROKEN) {
            CrimeSounds.lockpickBroke(player);
            CrimeStats.award(player, CrimeStats.LOCKPICKS_BROKEN);
        }
    }
}
