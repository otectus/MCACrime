package dev.otectus.mcacrime.enforcement;

import dev.otectus.mcacrime.McaCrime;
import dev.otectus.mcacrime.McaCrimeConfig;
import dev.otectus.mcacrime.bounty.BountyService;
import dev.otectus.mcacrime.compat.McaCompat;
import dev.otectus.mcacrime.detect.EntitySelectors;
import dev.otectus.mcacrime.event.AmbientMessages;
import dev.otectus.mcacrime.jail.HoldingCellService;
import dev.otectus.mcacrime.state.world.CrimeMaintenanceSweep;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

import org.jetbrains.annotations.Nullable;
import java.util.List;

/**
 * Drives guard pursuit of Legal-Target players and villager flee from Red players (spec §4, §4.4), on a
 * throttled server tick. The scan is bounded by player count (only online Legal-Target / Red players cost
 * anything) and a radius query — never a per-tick world scan (spec §20). Every MCA call is fail-safe: a
 * differing MCA version just makes the scan a no-op, never a crash.
 *
 * <h2>Challenge before force</h2>
 *
 * <p>The old shape of this scan was: see a Legal Target, print one chat line, wait sixty ticks, attack.
 * The line was not answerable and the wait was not a decision point, so in practice a Wanted player was
 * attacked on sight with a delay. Spec §13.1 is explicit that a default guard should not do that when a
 * safe arrest is available.
 *
 * <p>Now the scan hands off to {@link GuardChallengeService}, which opens a real encounter the player
 * can answer — surrender, pay, ask what the charges are, or refuse. Force is unlocked only once that
 * encounter has been refused or has run out, so being attacked is downstream of a choice the player
 * made. With {@code enableGuardChallenge} off, {@code forcePermitted} returns true immediately and the
 * original behaviour is back.
 */
@EventBusSubscriber(modid = McaCrime.MOD_ID)
public final class GuardEnforcement {

    private static int counter;
    private static final java.util.Map<java.util.UUID, Alert> ALERTS = new java.util.concurrent.ConcurrentHashMap<>();

    private record Alert(long until, String reasonKey) {
    }

    /**
     * The last walk order MCA: Crime gave a guard, so the scan can give it again.
     *
     * <p>Memory-only and bounded by the claim it belongs to. A guard with no live claim has its entry
     * dropped on the next scan, so this never outlives the enforcement action that created it.
     */
    private record WalkOrder(java.util.UUID target, double x, double y, double z, double speed, long until) {
    }

    private static final java.util.Map<java.util.UUID, WalkOrder> ORDERS =
            new java.util.concurrent.ConcurrentHashMap<>();

    private GuardEnforcement() {
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        int interval = Math.max(1, McaCrimeConfig.COMMON.guardScanIntervalTicks.get());
        if (++counter < interval) {
            return;
        }
        counter = 0;

        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) {
            return;
        }
        // Expiry and refusal cleanup ride the same throttled interval rather than the raw tick: an
        // encounter window is measured in seconds, and checking it ten times a second buys nothing.
        GuardChallengeService.tick(server);
        EscortService.tick(server);
        HoldingCellService.sweep(server);
        // Lapsed cell reservations ride along on the same throttled scan: a lease that ran out is a
        // slot a village is not using, and nothing else would ever collect it.
        dev.otectus.mcacrime.facility.CrimeFacilityService.sweep(server);
        // Rides this scan for the same reason everything else does: it is already throttled and already
        // holds the server, and a fifth ticker for a pass that examines one village a minute would be
        // four more than the work needs.
        GuardPopulationService.tick(server);
        // Criminal villagers: the chase, the escort and the sentence all ride this same scan. A
        // world with no arrested thieves pays two empty-map checks for them.
        NpcCriminalPursuit.tick(server);
        NpcCustodyService.tick(server, interval);
        // Bounty delivery rides the same scan for the same reason: it needs a guard near an outlaw,
        // which is precisely the thing this pass is already about (0.5.1).
        BountyService.tick(server);
        // Housekeeping rides the same scan, throttled again on top of it to once every five in-game
        // minutes: nothing it drops is urgent, and a pass that walks every loaded entity is not free.
        CrimeMaintenanceSweep.tick(server);
        respondToIncidents(server);

        long serverTime = server.overworld().getGameTime();
        LawHold.prune(serverTime);
        // Derived Townstead readings are bounded by their own interval on read; this only stops the
        // map growing for villagers that have since unloaded. Rides the scan because it walks entries.
        dev.otectus.mcacrime.compat.TownsteadSnapshotCache.sweep(serverTime);
        ALERTS.entrySet().removeIf(entry -> entry.getValue().until() < serverTime);
        double radius = McaCrimeConfig.COMMON.guardAggroRadius.get();

        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            try {
                if (!(player.level() instanceof ServerLevel level)) {
                    continue;
                }
                Alert alert = ALERTS.get(player.getUUID());
                boolean legalTarget = OutlawResolver.resolve(player).lawfulCombatTarget();
                if (ArrestPhases.inProgress(ArrestStates.phaseOf(player))
                        && !LegalTarget.isEscapedPrisoner(player)) {
                    // An arrest already owns this player, and both branches below would fight it.
                    //
                    // Pursuit would re-challenge and re-aggro: Heat and charges survive an arrest, so a
                    // prisoner stays a Legal Target for the whole walk. Stand-down is the subtler of the
                    // two and the reason this is a branch rather than a check inside pursue: surrender
                    // drops Heat below the Wanted threshold, so a player being escorted is very often
                    // *not* a Legal Target any more, and the stand-down path would clear the LawHold the
                    // escort had just taken -- once per scan, every scan, handing the guard back to the
                    // reaction ticker in the middle of walking somebody to a cell.
                    EscortService.noteOwned(player);
                } else if (legalTarget || alert != null || hasKnownCases(server, player, level.getGameTime())) {
                    pursue(level, player, alert, legalTarget, radius);
                } else {
                    standDown(level, player, radius);
                }
                // The reaction service now filters to this player's direct victims and their bounded
                // panic timers, so it must run regardless of the player's global band.
                VillagerReaction.fleeFrom(player);
            } catch (Throwable t) {
                McaCrime.LOGGER.debug("guard/villager enforcement failed for a player; continuing", t);
            }
        }
    }

    /**
     * Challenges, then escalates. The two are separate passes over the same guard list because they
     * have different ranges: a guard challenges from conversational distance and pursues from much
     * further, and using one radius for both would either have guards shouting charges across a field
     * or refusing to pursue anybody they had not already challenged.
     */
    private static void pursue(ServerLevel level, ServerPlayer player, @Nullable Alert alert,
                               boolean legalTarget, double radius) {
        long now = level.getGameTime();
        AABB box = player.getBoundingBox().inflate(radius);
        List<LivingEntity> guards = level.getEntitiesOfClass(LivingEntity.class, box,
                EntitySelectors::isResponder);
        guards = guards.stream().filter(guard -> dev.otectus.mcacrime.ai.NpcAwareness.canRespondAsGuard(guard)
                && !dev.otectus.mcacrime.state.world.CrimeWorldData.get(level.getServer()).isCaptive(guard.getUUID())
                && !ResponderAssignments.isEscorting(level.getServer(), guard.getUUID(), player.getUUID())
                && !ResponderAssignments.isCommitted(guard.getUUID(),
                        dev.otectus.mcacrime.activity.CrimeActivityView.Kind.PURSUIT, now)
                && !NpcCriminalPursuit.isAssignedElsewhere(guard.getUUID(), player.getUUID())).toList();
        guards = guards.stream().filter(guard -> {
            boolean authorized = dev.otectus.mcacrime.justice.JusticeService.forGuard(level, guard, player).mayChallenge();
            if (!authorized) McaCompat.clearGuardTarget(guard, player);
            return authorized;
        }).toList();
        if (guards.isEmpty()) {
            standDown(level, player, radius);
            return;
        }
        reassertWalkOrders(guards, player, now);

        if (LegalTarget.isEscapedPrisoner(player)) {
            LivingEntity recapturing = nearestWithin(guards, player,
                    Math.min(2.0D, McaCrimeConfig.COMMON.guardChallengeRadius.get()));
            if (recapturing != null) {
                if (ArrestService.arrest(player, recapturing, ArrestService.Cause.GUARD_INITIATED)
                        == ArrestService.Outcome.ALREADY_SERVING) GuardChallengeService.standDown(player);
            } else {
                approach(nearest(guards, player), player);
            }
            return;
        }

        double challengeRadius = McaCrimeConfig.COMMON.guardChallengeRadius.get();
        LivingEntity challenger = nearestWithin(guards, player, challengeRadius);
        if (challenger != null && GuardChallengeService.challenge(level, challenger, player)) {
            // An open encounter suppresses force entirely. The guard is talking, not swinging, so any
            // target left over from an approach has to be dropped or it swings mid-sentence.
            for (LivingEntity guard : guards) {
                McaCompat.clearGuardTarget(guard, player);
            }
            LawHold.hold(challenger, now + 3L * Math.max(1, McaCrimeConfig.COMMON.guardScanIntervalTicks.get()),
                    dev.otectus.mcacrime.activity.CrimeActivityView.Kind.CHALLENGE);
            McaCompat.holdPosition(challenger);
            McaCompat.faceEntity(challenger, player);
            return;
        }
        if (!GuardChallengeService.forcePermitted(player, now)) {
            // Not entitled to force yet. If nobody is in speaking range, one guard walks over.
            //
            // That needs its own primitive. `setGuardTarget` is how a guard is made to close distance,
            // but it is also how a guard is made to attack, so using it here would mean every challenge
            // was preceded by the swing it exists to avoid. Plain navigation moves the guard without
            // making it hostile, and the challenge opens when it arrives.
            if (challenger == null && McaCrimeConfig.COMMON.enableGuardChallenge.get()) {
                approach(nearest(guards, player), player);
            }
            return;
        }

        // Force is permitted. This used to sit behind the approach branch above, so a player who had
        // refused with every guard between the 6-block challenge radius and the 16-block pursuit radius
        // got one guard slowly walked toward them and no aggression at all -- indefinitely, if that
        // guard's path failed. Once force is lawful, distance is MCA's problem: its guard package
        // includes SetWalkTargetFromAttackTargetIfTargetOutOfReach, so a targeted guard closes on its own.
        // ...unless a relative is causing enough trouble that the guards cannot keep hold of anybody.
        // The window is short and one-shot: this suppresses re-acquisition, it does not clear a target
        // that is already set, which the action itself did once when it was asked for.
        if (AccompliceService.escapeHelpActive(player.getUUID(), now)) {
            return;
        }
        boolean targeted = false;
        long holdUntil = now + 3L * Math.max(1, McaCrimeConfig.COMMON.guardScanIntervalTicks.get());
        for (LivingEntity guard : guards) {
            if (McaCompat.setGuardTarget(guard, player)) {
                // Hold the guard against the reaction system, which ticks twice as often as this scan
                // and would otherwise clear the target on its way out of a panic controller.
                LawHold.hold(guard, holdUntil, dev.otectus.mcacrime.activity.CrimeActivityView.Kind.PURSUIT);
                targeted = true;
            }
        }
        if (targeted) {
            AmbientMessages.noteGuardAggro(player, legalTarget
                    ? LegalTarget.primaryReasonKey(player)
                    : alert == null ? "mcacrime.msg.guardaggro.generic" : alert.reasonKey());
        }
    }

    /** Case-based activation supplements Wanted status; responders still select local cases below. */
    private static boolean hasKnownCases(MinecraftServer server, ServerPlayer player, long now) {
        var configured = dev.otectus.mcacrime.justice.JusticeService.Settings.fromConfig();
        var publicScope = new dev.otectus.mcacrime.justice.JusticeService.Settings(
                configured.observations(), true, configured.confidence());
        return dev.otectus.mcacrime.justice.JusticeService.evaluate(
                dev.otectus.mcacrime.state.world.CrimeWorldData.get(server), player.getUUID(), null, now,
                publicScope, false, false).mayChallenge();
    }

    /**
     * Drops the scan's memory-only state. Called on server stop, beside the other enforcement caches.
     *
     * <p>Both maps describe the current few seconds — an alert that has not expired yet and a walk
     * order a guard has not carried out — so a restart inheriting either would be describing an
     * enforcement action nobody is taking any more.
     */
    public static void clearAll() {
        ALERTS.clear();
        ORDERS.clear();
    }

    /** Walks one guard toward the suspect without making it hostile. Best-effort; failure is a no-op. */
    private static void approach(@Nullable LivingEntity guard, ServerPlayer player) {
        if (guard == null) {
            return;
        }
        long until = player.level().getGameTime()
                + 3L * Math.max(1, McaCrimeConfig.COMMON.guardScanIntervalTicks.get());
        McaCompat.faceEntity(guard, player);
        LawHold.hold(guard, until, dev.otectus.mcacrime.activity.CrimeActivityView.Kind.PURSUIT);
        if (McaCompat.moveVillagerTo(guard, player.getX(), player.getY(), player.getZ(), 1.1)) {
            ORDERS.put(guard.getUUID(), new WalkOrder(player.getUUID(),
                    player.getX(), player.getY(), player.getZ(), 1.1, until));
        }
    }

    /**
     * Whether a guard's walk order has to be given again this scan.
     *
     * <p>Pure, because the interesting part of it is a judgement rather than a lookup. A settlement
     * companion that manages villager rest can erase {@code WALK_TARGET} and stop the navigator on
     * <em>every</em> tick for a guard it considers off duty — Townstead's {@code GuardRestEnforcerTicker}
     * does exactly that for a guard in REST with no attack target and no home. MCA: Crime's scan runs
     * once every {@code guardScanIntervalTicks}, so left alone the guard would take one step per scan
     * and be stopped nine times in between.
     *
     * <p>Re-asserting is the Crime-side half of the fix and is deliberately cheap: while the guard
     * holds a live claim, the order is simply given again. The other half — not erasing it in the
     * first place — belongs upstream, and the hook it consults is
     * {@code CrimeActivityRegistry.activeFor}.
     */
    public static boolean shouldReassertWalkOrder(boolean claimActive, boolean orderStillWanted,
                                                  boolean walkTargetPresent) {
        return claimActive && orderStillWanted && !walkTargetPresent;
    }

    /**
     * Whether a lost walk order is a path failure or just somebody else's ticker.
     *
     * <p>A guard whose {@code WALK_TARGET} vanished while MCA: Crime holds a claim on them has not
     * failed to reach anything — nothing even tried. Counting it as a failure is what turns an
     * external stop into a give-up, and a give-up into a suspect nobody ever walks over to.
     */
    public static boolean walkTargetLossCountsAsFailure(boolean claimActive, boolean walkTargetPresent) {
        return !walkTargetPresent && !claimActive;
    }

    /**
     * Gives claimed guards their walk order again, once per scan.
     *
     * <p>Only guards MCA: Crime is actually holding, only while the order is still wanted, and only
     * when the order is gone: a guard still walking is left alone, so this costs one memory read per
     * claimed guard on a scan that has already queried the world.
     */
    private static void reassertWalkOrders(List<LivingEntity> guards, ServerPlayer player, long now) {
        if (ORDERS.isEmpty()) {
            return;
        }
        for (LivingEntity guard : guards) {
            WalkOrder order = ORDERS.get(guard.getUUID());
            if (order == null) {
                continue;
            }
            boolean claimActive = dev.otectus.mcacrime.activity.CrimeActivityRegistry
                    .activeFor(guard.getUUID(), now).isPresent();
            boolean wanted = order.until() > now && order.target().equals(player.getUUID())
                    && LawHold.isHeld(guard.getUUID(), now);
            if (!claimActive || !wanted) {
                if (!claimActive || order.until() <= now) {
                    ORDERS.remove(guard.getUUID());
                }
                continue;
            }
            boolean walkTarget = guard.getBrain()
                    .hasMemoryValue(net.minecraft.world.entity.ai.memory.MemoryModuleType.WALK_TARGET);
            if (shouldReassertWalkOrder(true, true, walkTarget)) {
                // Deliberately not counted as a path failure: see walkTargetLossCountsAsFailure.
                McaCompat.moveVillagerTo(guard, player.getX(), player.getY(), player.getZ(), order.speed());
            }
        }
    }

    @Nullable
    private static LivingEntity nearest(List<LivingEntity> guards, ServerPlayer player) {
        LivingEntity best = null;
        double bestDistance = Double.MAX_VALUE;
        for (LivingEntity guard : guards) {
            double distance = guard.distanceToSqr(player);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = guard;
            }
        }
        return best;
    }

    /** No legal basis any more: drop targets, and close any encounter still open against them. */
    private static void standDown(ServerLevel level, ServerPlayer player, double radius) {
        AABB box = player.getBoundingBox().inflate(radius);
        for (LivingEntity guard : level.getEntitiesOfClass(LivingEntity.class, box,
                EntitySelectors::isResponder)) {
            McaCompat.clearGuardTarget(guard, player);
            if (!ResponderAssignments.isEscorting(level.getServer(), guard.getUUID(), player.getUUID())
                    && !NpcCriminalPursuit.isAssignedElsewhere(guard.getUUID(), player.getUUID())) {
                LawHold.clear(guard.getUUID());
                ORDERS.remove(guard.getUUID());
            }
        }
        // A recovery window expires on the player's own clock; Heat decaying out from under a failed
        // arrest must not cut it short, or the guard that just failed re-challenges immediately.
        ArrestPhase phase = ArrestStates.phaseOf(player);
        if ((phase == ArrestPhase.NONE || phase == ArrestPhase.CONFRONTED)
                && GuardChallengeService.open(player.getUUID()) != null) {
            GuardChallengeService.standDown(player);
        }
    }

    @Nullable
    private static LivingEntity nearestWithin(List<LivingEntity> guards, ServerPlayer player, double radius) {
        double limit = radius * radius;
        LivingEntity best = null;
        double bestDistance = Double.MAX_VALUE;
        for (LivingEntity guard : guards) {
            double distance = guard.distanceToSqr(player);
            // Fresh selection, so it uses the sand-aware check: a blinded guard is not the one who
            // spots a suspect across the square (0.7.2 §13.5). Guards already on a case keep it.
            if (distance <= limit && distance < bestDistance
                    && dev.otectus.mcacrime.ai.NpcAwareness.canSeeNow(guard, player)) {
                bestDistance = distance;
                best = guard;
            }
        }
        return best;
    }

    /**
     * Guards react to the muggings happening right now, not only to the ones that get reported.
     *
     * <p>Two conditions, and the second is the one the spec is emphatic about: the incident has to be
     * at {@link ActiveIncidentRegistry.Phase#THREAT} or later, and a guard has to actually be able to
     * see the offender. A thief that is scouting or approaching has committed nothing observable and
     * opens no incident at all, so it is invisible here -- spec §"Guard intervention" ends on exactly
     * that test, because guard-avoidance AI is worthless against psychic guards.
     */
    private static void respondToIncidents(MinecraftServer server) {
        java.util.Collection<ActiveIncidentRegistry.ActiveIncident> incidents = ActiveIncidentRegistry.all();
        if (incidents.isEmpty()) {
            return;
        }
        double radius = McaCrimeConfig.COMMON.guardThiefResponseRadius.get();
        for (ActiveIncidentRegistry.ActiveIncident incident : incidents) {
            if (!ActiveIncidentRegistry.visibleToGuards(incident.phase())
                    || NpcCriminalPursuit.isPursued(incident.offenderId())) {
                continue;
            }
            ServerLevel level = server.getLevel(incident.dimension());
            if (level == null || !(level.getEntity(incident.offenderId()) instanceof LivingEntity offender)
                    || !offender.isAlive()) {
                continue;
            }
            LivingEntity witness = nearestWitness(level, offender, radius);
            if (witness != null) {
                NpcCriminalPursuit.engage(level, witness, incident);
            }
        }
    }

    /** The closest responder that can see this offender, or null when none can. */
    @Nullable
    private static LivingEntity nearestWitness(ServerLevel level, LivingEntity offender, double radius) {
        AABB box = offender.getBoundingBox().inflate(radius);
        LivingEntity best = null;
        double bestDistance = Double.MAX_VALUE;
        for (LivingEntity guard : level.getEntitiesOfClass(LivingEntity.class, box,
                entity -> entity != offender && EntitySelectors.isAvailableResponder(entity))) {
            if (ResponderAssignments.isEscorting(level.getServer(), guard.getUUID(), offender.getUUID())
                    || NpcCriminalPursuit.isAssignedElsewhere(guard.getUUID(), offender.getUUID())) continue;
            double distance = guard.distanceToSqr(offender);
            if (distance < bestDistance
                    && dev.otectus.mcacrime.ai.NpcAwareness.canSeeNow(guard, offender)) {
                bestDistance = distance;
                best = guard;
            }
        }
        return best;
    }

    /**
     * Drops every chase and every escort's bookkeeping on shutdown, and rebuilds the latter on start.
     * Custody itself is in world data and needs neither.
     */
    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        NpcCustodyService.reconcile(event.getServer());
    }

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        NpcCriminalPursuit.clearAll();
        NpcCustodyService.clearAll();
        BountyService.clearAll();
    }

    /** A direct victim/witness report gives nearby guards a bounded basis to challenge this player. */
    public static void alert(ServerPlayer player, String reasonKey, long durationTicks) {
        long now = player.level().getGameTime();
        ALERTS.put(player.getUUID(), new Alert(now + Math.max(1L, durationTicks), reasonKey));
    }

    /** Drops a player's alert on logout so the map cannot grow for the life of the server. */
    public static void forget(java.util.UUID player) {
        ALERTS.remove(player);
    }
}
