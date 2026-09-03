package dev.otectus.mcacrime.enforcement;

import dev.otectus.mcacrime.McaCrimeConfig;
import dev.otectus.mcacrime.captivity.CustodyOwner;
import dev.otectus.mcacrime.captivity.CustodyService;
import dev.otectus.mcacrime.compat.McaCompat;
import dev.otectus.mcacrime.detect.EntitySelectors;
import dev.otectus.mcacrime.engine.CrimeState;
import dev.otectus.mcacrime.jail.JailAnchor;
import dev.otectus.mcacrime.jail.JailRegion;
import dev.otectus.mcacrime.jail.JailService;
import dev.otectus.mcacrime.network.CrimeNetwork;
import dev.otectus.mcacrime.state.CrimeAttachments;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;

import org.jetbrains.annotations.Nullable;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Walks an arrested player to their cell.
 *
 * <p>The escort is flavour; the sentence is the mechanic. That ordering decides every hard case here.
 * A guard who dies, unloads, or simply cannot path must not be able to cancel a sentence the player
 * already accepted, so every failure completes the arrest by teleport rather than abandoning it — being
 * arrested is not something a pathfinding failure should undo.
 *
 * <p>The one exception is the tether. A player who surrenders and then sprints away has not been failed
 * by the escort; they have changed their mind, and the honest answer is to let them go and mark them
 * resisting arrest, which is exactly what they are doing. Dragging them back by teleport would read as
 * a bug, and a rule that reads as a bug is worse than no rule.
 *
 * <p>The escort <em>state</em> now lives on the player's {@link ArrestState} rather than only in the map
 * below, so it survives a logout and a restart. The map remains as the fast index of who is walking
 * right now; it is a cache of the phase, never a second opinion about it.
 *
 * <p>Ticked from the enforcement scan rather than from a ticker of its own — that scan already runs on
 * a throttle and already holds the server.
 */
public final class EscortService {

    /** What the escort should do this tick. Pure, so the decision can be tested without a world. */
    public enum Step {
        /** Keep walking. */
        CONTINUE,
        /** Inside the jail region: put them in the cell. */
        ARRIVED,
        /** The prisoner ran. Release custody and flag them resisting. */
        TETHER_BROKEN,
        /** Out of time, stuck, or the escort has nobody to lead it. Jail them where they stand. */
        COMPLETE_BY_TELEPORT
    }

    private static final Map<UUID, Long> ACTIVE = new ConcurrentHashMap<>();

    private EscortService() {
    }

    /**
     * The escort decision, as a pure function of the facts.
     *
     * <p>Extracted because this is the part with the interesting behaviour and none of the interesting
     * dependencies: the ordering of the checks is the design, and it is the only thing here worth
     * asserting in a test. Arrival is checked before everything else so an escort that lands on the
     * last tick, or lands while the guard is stuck against a door, counts as having arrived rather than
     * as having failed.
     *
     * <p>{@code stuck} completes by teleport rather than abandoning, and that asymmetry with the tether
     * is deliberate: a guard that cannot find its way is the mod failing the player, while a prisoner
     * outside the tether is the player deciding something.
     */
    public static Step decide(boolean guardPresent, boolean prisonerInJailRegion,
                              double prisonerDistanceSqr, double tetherSqr,
                              boolean stuck, long now, long deadline) {
        if (prisonerInJailRegion) {
            return Step.ARRIVED;
        }
        if (guardPresent && prisonerDistanceSqr > tetherSqr) {
            return Step.TETHER_BROKEN;
        }
        if (!guardPresent || stuck || now >= deadline) {
            return Step.COMPLETE_BY_TELEPORT;
        }
        return Step.CONTINUE;
    }

    /**
     * The pre-0.4.0 decision, kept so callers and tests written against a radius-based arrival still
     * compile and still mean what they meant.
     *
     * @deprecated arrival is now "inside the jail region", the same test {@code JailConfine} and
     *             {@code ContainmentHandler} use, so that arriving and being confined finally agree.
     */
    @Deprecated
    public static Step decide(boolean guardPresent, double prisonerDistanceSqr, double tetherSqr,
                              double destinationDistanceSqr, double arrivalSqr, long now, long deadline) {
        return decide(guardPresent, destinationDistanceSqr <= arrivalSqr, prisonerDistanceSqr, tetherSqr,
                false, now, deadline);
    }

    /**
     * The strike count after this scan: reset when the prisoner got meaningfully closer to the cell,
     * incremented when they did not.
     *
     * <p>Pure. "Meaningfully" is a squared-distance margin rather than an exact comparison, so a guard
     * shuffling on the spot against a fence post does not read as progress forever.
     */
    public static int nextStrikes(double bestDistanceSqr, double currentDistanceSqr, int strikes) {
        return currentDistanceSqr + 1.0 < bestDistanceSqr ? 0 : Math.max(0, strikes) + 1;
    }

    /** Whether the escort has gone long enough without progress to stop walking and finish by teleport. */
    public static boolean isStuck(int strikes, int limit) {
        return limit > 0 && strikes >= limit;
    }

    /**
     * Whether a position change between two scans is a teleport rather than a sprint.
     *
     * <p>A player cannot cross the tether distance under their own power inside one scan interval, so a
     * jump that large is an operator {@code /tp}, a portal, or another mod moving them. Treating it as
     * a tether break would mean any of those silently cancelled an arrest and marked the player
     * resisting for something they did not do.
     */
    public static boolean looksLikeTeleport(double movedSqr, double tetherSqr) {
        return movedSqr > tetherSqr;
    }

    /** Starts an escort. With {@code arrestEscortTimeoutTicks} at zero the arrest completes at once. */
    public static void begin(ServerPlayer player, @Nullable LivingEntity guard, JailAnchor anchor,
                             long sentenceTicks) {
        long timeout = McaCrimeConfig.COMMON.arrestEscortTimeoutTicks.get();
        if (timeout <= 0L || guard == null) {
            complete(player, anchor, sentenceTicks, guard);
            return;
        }
        ArrestStates.transition(player, ArrestPhase.ESCORTING);
        ACTIVE.put(player.getUUID(), player.level().getGameTime());
        player.sendSystemMessage(Component.translatable("mcacrime.arrest.escorting",
                ArrestService.nameOf(guard)));
        player.sendSystemMessage(Component.translatable("mcacrime.arrest.restrained",
                ArrestService.nameOf(guard)));
    }

    /** Advances every running escort. Called from the throttled enforcement scan. */
    public static void tick(MinecraftServer server) {
        if (ACTIVE.isEmpty()) {
            return;
        }
        for (UUID prisonerId : List.copyOf(ACTIVE.keySet())) {
            ServerPlayer prisoner = server.getPlayerList().getPlayer(prisonerId);
            if (prisoner == null) {
                // Logged out mid-escort. The arrest record persists on the player and login
                // reconciliation finishes the job; keeping the escort would be walking a guard toward
                // nobody.
                ACTIVE.remove(prisonerId);
                continue;
            }
            ArrestState state = ArrestStates.of(prisoner);
            if (state == null || state.getPhase() != ArrestPhase.ESCORTING) {
                ACTIVE.remove(prisonerId);
                continue;
            }
            step(server, prisoner, state);
        }
    }

    private static void step(MinecraftServer server, ServerPlayer prisoner, ArrestState state) {
        if (!(prisoner.level() instanceof ServerLevel level)) {
            return;
        }
        JailAnchor anchor = state.anchor();
        if (anchor == null) {
            // The destination went missing between the arrest and here. Never hold a restrained player
            // for a cell that does not exist.
            ACTIVE.remove(prisoner.getUUID());
            GuardChallengeService.standDownAndRecover(prisoner, "mcacrime.arrest.recovery");
            releaseCustodyQuietly(server, prisoner);
            return;
        }

        Entity guard = state.getGuard() == null ? null : level.getEntity(state.getGuard());
        if (guard == null || !guard.isAlive()) {
            guard = reassign(level, prisoner, state);
        }
        boolean guardPresent = guard != null && guard.isAlive();

        double tether = McaCrimeConfig.COMMON.escortTetherBlocks.get();
        double tetherSqr = tether * tether;

        // Distinguish "they ran" from "something moved them" before the tether is consulted.
        BlockPos here = prisoner.blockPosition();
        BlockPos last = state.getLastSeenPos();
        if (last != null && guardPresent && looksLikeTeleport(here.distSqr(last), tetherSqr)) {
            prisoner.teleportTo(level, guard.getX(), guard.getY(), guard.getZ(),
                    prisoner.getYRot(), prisoner.getXRot());
            state.setLastSeenPos(prisoner.blockPosition());
            return;
        }
        state.setLastSeenPos(here);

        BlockPos destination = anchor.pos();
        double destinationSqr = prisoner.distanceToSqr(destination.getX() + 0.5, destination.getY(),
                destination.getZ() + 0.5);
        int strikes = nextStrikes(state.getBestAnchorDistanceSqr(), destinationSqr, state.getStuckStrikes());
        state.setStuckStrikes(strikes);
        state.setBestAnchorDistanceSqr(Math.min(state.getBestAnchorDistanceSqr(), destinationSqr));

        boolean inRegion = JailRegion.contains(destination, anchor.radius(), anchor.dim(),
                here, level.dimension().location());
        long online = CrimeAttachments.get(prisoner).getOnlineTicksLived();

        Step step = decide(guardPresent, inRegion,
                guardPresent ? guard.distanceToSqr(prisoner) : 0.0,
                tetherSqr,
                isStuck(strikes, McaCrimeConfig.COMMON.escortStuckScans.get()),
                online, state.getDeadlineOnlineTick() <= 0L ? Long.MAX_VALUE : state.getDeadlineOnlineTick());

        switch (step) {
            case CONTINUE -> walk(level, guard, prisoner, state, destination);
            case ARRIVED -> complete(prisoner, anchor, state.getSentenceTicks(),
                    guard instanceof LivingEntity living ? living : null);
            case COMPLETE_BY_TELEPORT -> {
                prisoner.sendSystemMessage(Component.translatable("mcacrime.arrest.escort_lost"));
                complete(prisoner, anchor, state.getSentenceTicks(),
                        guard instanceof LivingEntity living ? living : null);
            }
            case TETHER_BROKEN -> abandon(server, prisoner, state);
        }
    }

    /**
     * Issues the walk order, on a cadence rather than every scan.
     *
     * <p>An MCA villager runs its own brain, so a navigation order reissued constantly fights it and the
     * guard visibly stutters. The order is refreshed when the interval has elapsed or when the previous
     * path has finished, which is enough to route around a closed door without arguing with MCA about
     * every step.
     */
    private static void walk(ServerLevel level, @Nullable Entity guard, ServerPlayer prisoner,
                             ArrestState state, BlockPos destination) {
        if (guard == null) {
            return;
        }
        // Hold the guard against the reaction system for a few scans. Without this the reaction ticker,
        // which runs twice as often as this scan, clears the escort target on its way out of a panic
        // controller -- the same race LawHold was written for.
        long now = level.getGameTime();
        LawHold.hold(state.getGuard(), now + 3L * McaCrimeConfig.COMMON.guardScanIntervalTicks.get());
        McaCompat.faceEntity(guard, prisoner);
        long interval = McaCrimeConfig.COMMON.escortNavigationIntervalTicks.get();
        if (now - state.getLastNavigationTick() < interval && !McaCompat.navigationDone(guard)) {
            return;
        }
        state.setLastNavigationTick(now);
        McaCompat.moveVillagerTo(guard, destination.getX() + 0.5, destination.getY(),
                destination.getZ() + 0.5, McaCrimeConfig.COMMON.escortWalkSpeed.get());
    }

    /**
     * Finds another responder to finish the walk after the first one died, despawned, or unloaded.
     *
     * <p>A dead escort used to mean an instant teleport to the cell. Handing the prisoner to whoever
     * else is standing there keeps the arrest physical for as long as the village can manage it, and
     * only a village with no law left at all falls back to the teleport.
     */
    @Nullable
    private static Entity reassign(ServerLevel level, ServerPlayer prisoner, ArrestState state) {
        double radius = McaCrimeConfig.COMMON.guardAggroRadius.get();
        LivingEntity best = null;
        double bestDistance = Double.MAX_VALUE;
        for (LivingEntity candidate : level.getEntitiesOfClass(LivingEntity.class,
                prisoner.getBoundingBox().inflate(radius), EntitySelectors::isResponder)) {
            double distance = candidate.distanceToSqr(prisoner);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = candidate;
            }
        }
        if (best == null) {
            return null;
        }
        ArrestStates.reassignGuard(prisoner, best.getUUID());
        MinecraftServer server = level.getServer();
        if (server != null && ArrestService.inLawfulCustody(server, prisoner.getUUID())) {
            CustodyService.transferLawfulCustody(server, prisoner.getUUID(),
                    CustodyOwner.guard(best.getUUID()));
        }
        prisoner.sendSystemMessage(Component.translatable("mcacrime.arrest.escort_reassigned",
                ArrestService.nameOf(best)));
        return best;
    }

    /**
     * Puts the prisoner in the cell and hands custody from the arresting guard to the jail itself.
     *
     * <p>Custody is transferred rather than released and retaken: a release would fire the public
     * events a second time and leave the player momentarily free, which any listener watching for
     * escapes would reasonably read as one.
     */
    public static void complete(ServerPlayer prisoner, JailAnchor anchor, long sentenceTicks,
                                @Nullable LivingEntity guard) {
        ACTIVE.remove(prisoner.getUUID());
        MinecraftServer server = prisoner.getServer();
        if (server == null) {
            return;
        }
        ArrestState state = ArrestStates.of(prisoner);
        UUID sentenceId = state == null ? null : state.getSentenceId();
        if (!JailService.jail(prisoner, sentenceTicks, anchor, sentenceId, false)) {
            // The anchor stopped resolving between the arrest and here. Do not leave the player in
            // custody with no sentence: let them go and let the guard start over.
            releaseCustodyQuietly(server, prisoner);
            GuardChallengeService.standDownAndRecover(prisoner, "mcacrime.arrest.recovery");
            return;
        }
        CustodyService.transferLawfulCustody(server, prisoner.getUUID(),
                CustodyOwner.jail(-1, anchor.pos(), anchor.dim()));
        if (guard != null) {
            LawHold.clear(guard.getUUID());
            McaCompat.clearGuardTarget(guard, prisoner);
        }
        // The sentence has started, so the phase moves before the stand-down clears the arrest record.
        ArrestStates.transition(prisoner, ArrestPhase.JAILED);
        GuardChallengeService.standDown(prisoner);
        CrimeState.setResistingArrest(prisoner, false);
        prisoner.sendSystemMessage(Component.translatable("mcacrime.arrest.arrived"));
        CrimeNetwork.sendSelfStatus(prisoner);
    }

    /** The prisoner ran. Custody ends, and running from a surrender is itself resisting arrest. */
    private static void abandon(MinecraftServer server, ServerPlayer prisoner, ArrestState state) {
        ACTIVE.remove(prisoner.getUUID());
        UUID guard = state.getGuard();
        ArrestStates.clear(prisoner);
        releaseCustodyQuietly(server, prisoner);
        CrimeState.setResistingArrest(prisoner, true);
        prisoner.sendSystemMessage(Component.translatable("mcacrime.arrest.fled"));
        if (guard != null) {
            LawHold.clear(guard);
        }
    }

    private static void releaseCustodyQuietly(MinecraftServer server, ServerPlayer prisoner) {
        ACTIVE.remove(prisoner.getUUID());
        if (ArrestService.inLawfulCustody(server, prisoner.getUUID())) {
            CustodyService.release(server, prisoner.getUUID(),
                    dev.otectus.mcacrime.captivity.CustodyReleaseReason.ADMIN);
        }
    }

    /**
     * Drops any leftover hostility toward a player an arrest already owns, without touching the
     * escort's own hold on its guard.
     *
     * <p>Called from the enforcement scan in place of both pursuit and stand-down. A guard that was
     * swinging before the surrender has to be told to stop, but the guard doing the escorting must keep
     * its {@link LawHold}, so this clears targets and deliberately does not clear holds.
     */
    public static void noteOwned(ServerPlayer prisoner) {
        if (!(prisoner.level() instanceof ServerLevel level)) {
            return;
        }
        ArrestState state = ArrestStates.of(prisoner);
        java.util.UUID escort = state == null ? null : state.getGuard();
        double radius = McaCrimeConfig.COMMON.guardAggroRadius.get();
        for (LivingEntity guard : level.getEntitiesOfClass(LivingEntity.class,
                prisoner.getBoundingBox().inflate(radius), EntitySelectors::isResponder)) {
            if (guard.getUUID().equals(escort)) {
                continue; // the escort is walking them, not fighting them; leave its hold alone
            }
            McaCompat.clearGuardTarget(guard, prisoner);
            LawHold.clear(guard.getUUID());
        }
    }

    /** Puts a reconciled escort back in the running index after a relog. */
    public static void resume(ServerPlayer prisoner) {
        ACTIVE.put(prisoner.getUUID(), prisoner.level().getGameTime());
    }

    /** Drops a player's escort on logout, so the map cannot grow for the life of the server. */
    public static void forget(UUID prisoner) {
        ACTIVE.remove(prisoner);
    }

    /** Drops every escort. Called on server stop. */
    public static void clearAll() {
        ACTIVE.clear();
    }

    /** Exposed for {@code /crime debug}: how many escorts are running. */
    public static int activeCount() {
        return ACTIVE.size();
    }
}
