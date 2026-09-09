package dev.otectus.mcacrime.ai.thief;

import dev.otectus.mcacrime.McaCrimeConfig;
import dev.otectus.mcacrime.api.event.CrimeIntentEvent;
import dev.otectus.mcacrime.compat.McaCompat;
import dev.otectus.mcacrime.crime.type.CrimeIds;
import dev.otectus.mcacrime.detect.EntitySelectors;
import dev.otectus.mcacrime.economy.Currencies;
import dev.otectus.mcacrime.item.weapon.WeaponDetector;
import dev.otectus.mcacrime.job.CriminalJob;
import dev.otectus.mcacrime.job.WorldCriminalJobService;
import dev.otectus.mcacrime.mug.npc.NpcMugSession;
import dev.otectus.mcacrime.mug.npc.NpcMuggingService;
import dev.otectus.mcacrime.state.world.CriminalVillagerRecord;
import dev.otectus.mcacrime.state.world.CrimeWorldData;
import dev.otectus.mcacrime.util.CrimeDebug;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForge;

import org.jetbrains.annotations.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Every loaded thief's live behaviour (spec §"Thief behavioral state machine").
 *
 * <p>Shaped like {@code CrimeReactionService} on purpose, including the parts that look like
 * restraint: controllers exist only for thieves that are actually loaded, each decides for itself
 * whether this is one of its think ticks, and the world queries behind a decision run on a jittered
 * scan interval rather than every tick. A world with fifty thieves and nobody online does one map
 * walk per tick and no scans at all — never all players multiplied by all thieves multiplied by all
 * guards.
 *
 * <p>Movement goes through the {@code McaCompat} wrappers, never through a goal selector or a brain
 * injection: MCA owns the villager, and this borrows it for a few seconds and gives it back.
 */
public final class ThiefBehaviorService {

    /** thief id -> live controller. Memory-only; the job and the mug cooldown are what persist. */
    private static final Map<UUID, ThiefBehaviorController> ACTIVE = new ConcurrentHashMap<>();

    /** How often a controller makes a decision. Scans and risk are rarer still. */
    private static final int THINK_INTERVAL_TICKS = 10;
    /** Guard risk is re-cached on this cadence while a thief is doing anything at all. */
    private static final int RISK_INTERVAL_TICKS = 15;
    /** How close the thief must be before it will make the threat. */
    private static final double THREAT_REACH = 3.0D;
    /** Radius counted as "somebody else is standing here" for the isolation and crowd terms. */
    private static final double CROWD_RADIUS = 12.0D;
    /** How far the escape runs before the thief considers itself away. */
    private static final double FLEE_DISTANCE = 20.0D;
    /** A flee that has not arrived by now is not going to; the thief settles down where it is. */
    private static final int FLEE_TIMEOUT_TICKS = 300;

    private static final double APPROACH_SPEED = 1.05D;
    private static final double FLEE_SPEED = 1.3D;

    private ThiefBehaviorService() {
    }

    // ------------------------------------------------------------------ registry

    /** Starts driving this villager, if it is a thief and thieves are enabled. */
    public static void track(LivingEntity thief) {
        if (thief == null || !McaCrimeConfig.COMMON.enableThieves.get()) {
            return;
        }
        if (!(thief.level() instanceof ServerLevel level) || !McaCompat.isMcaVillager(thief)) {
            return;
        }
        MinecraftServer server = level.getServer();
        if (server == null || WorldCriminalJobService.of(server).get(thief.getUUID()) != CriminalJob.THIEF) {
            return;
        }
        ThiefBehaviorController controller = ACTIVE.computeIfAbsent(thief.getUUID(), id ->
                new ThiefBehaviorController(id, level.dimension().location(), level.getGameTime()));
        reconcileCustody(controller, CrimeWorldData.get(server).isCaptive(thief.getUUID()),
                level.getGameTime(), ThiefPolicy.fromConfig().mugCooldownTicks());
    }

    /** Stops driving this villager and hands it back to MCA if it is still loaded. */
    public static void untrack(UUID thief) {
        ThiefBehaviorController controller = thief == null ? null : ACTIVE.remove(thief);
        if (controller == null) {
            return;
        }
        MinecraftServer server = net.neoforged.neoforge.server.ServerLifecycleHooks.getCurrentServer();
        ServerLevel level = levelOf(server, controller.dimension());
        if (level != null && level.getEntity(thief) instanceof LivingEntity entity
                && !CrimeWorldData.get(server).isCaptive(thief)) {
            McaCompat.releaseVillagerControl(entity);
        }
    }

    public static ThiefState stateOf(UUID thief) {
        ThiefBehaviorController controller = thief == null ? null : ACTIVE.get(thief);
        return controller == null ? ThiefState.IDLE : controller.state();
    }

    public static Optional<ThiefBehaviorController> controller(UUID thief) {
        return Optional.ofNullable(thief == null ? null : ACTIVE.get(thief));
    }

    /** Every live controller, for {@code /crime debug thieves}. */
    public static List<ThiefBehaviorController> snapshot() {
        return new ArrayList<>(ACTIVE.values());
    }

    public static int activeCount() {
        return ACTIVE.size();
    }

    /** Drops every controller. Called on server stop so a restart never inherits a walk. */
    public static void clearAll() {
        for (UUID thief : List.copyOf(ACTIVE.keySet())) {
            untrack(thief);
        }
        ACTIVE.clear();
    }

    // ------------------------------------------------------------------ external transitions

    /**
     * The mug is off; run.
     *
     * <p>Spec §"Player mugging interaction" allows an armed thief to fight an unarmed victim instead.
     * It flees in every case here: a thief that stands and fights is a hostile mob with extra steps,
     * and the fight would land on MCA's combat brain rather than on anything this mod owns.
     */
    public static void enterFleeOrFight(UUID thief) {
        ThiefBehaviorController controller = thief == null ? null : ACTIVE.get(thief);
        if (controller == null || controller.state() == ThiefState.ARRESTED
                || controller.state() == ThiefState.DEAD) {
            return;
        }
        controller.setVictim(null);
        controller.setTransactionId(null);
        controller.enter(ThiefState.FLEEING, gameTime(controller));
    }

    /**
     * A guard took this thief into custody. Enter ARRESTED immediately, before another controller tick.
     */
    public static void markArrested(UUID thief) {
        ThiefBehaviorController controller = thief == null ? null : ACTIVE.get(thief);
        if (controller == null) {
            return;
        }
        reconcileCustody(controller, true, gameTime(controller), 0L);
    }

    /**
     * Custody let this thief go. It goes back to work, eventually.
     *
     * <p>{@link ThiefState#ARRESTED} is the one state the pure machine cannot leave on its own — by
     * design, since nothing the thief can observe tells it a sentence is over. This is the call that
     * ends it, and it lands in COOLDOWN rather than SCOUTING so a released thief does not walk out of
     * the cell and rob the first person standing outside it.
     */
    public static void markReleased(UUID thief) {
        ThiefBehaviorController controller = thief == null ? null : ACTIVE.get(thief);
        if (controller == null || controller.state() != ThiefState.ARRESTED) {
            return;
        }
        long now = gameTime(controller);
        controller.enter(ThiefState.COOLDOWN, now);
        controller.setCooldownUntil(now + ThiefPolicy.fromConfig().mugCooldownTicks());
        controller.thinkAsSoonAsPossible();
    }

    /**
     * Points a thief at a player and starts the approach, ignoring the scan entirely.
     *
     * <p>The hook behind {@code /crime mugtest}: waiting for a jittered scan to pick you is a poor
     * way to test a four-second interaction, and every gate after selection — guard risk, reach, the
     * weapon check, the cancellable attempt event — still applies exactly as it would have.
     *
     * @return false when the entity is not a tracked thief, or is not in a state to start one
     */
    public static boolean forceTarget(LivingEntity thief, ServerPlayer victim) {
        if (thief == null || !(thief.level() instanceof ServerLevel level)
                || !NpcMuggingService.canTarget(level, thief, victim)
                || NpcMuggingService.isVictim(victim.getUUID())
                || NpcMuggingService.sessionForThief(thief.getUUID()).isPresent()) {
            return false;
        }
        track(thief);
        ThiefBehaviorController controller = ACTIVE.get(thief.getUUID());
        if (controller == null || controller.state() == ThiefState.ARRESTED
                || controller.state() == ThiefState.DEAD || targetedByAnother(controller, victim.getUUID())) {
            return false;
        }
        long now = victim.level().getGameTime();
        controller.clearFailures();
        controller.setCooldownUntil(0L);
        controller.setVictim(victim.getUUID());
        controller.enter(ThiefState.APPROACHING, now);
        controller.thinkAsSoonAsPossible();
        CrimeDebug.crime("mugtest: thief {} forced onto victim {}", thief.getUUID(), victim.getUUID());
        return true;
    }

    /** Called by {@code NpcMuggingService} however a session ended: cooldown, then run. */
    public static void onMugEnded(UUID thief) {
        ThiefBehaviorController controller = thief == null ? null : ACTIVE.get(thief);
        if (controller == null) {
            return;
        }
        controller.noteFailure();
        enterFleeOrFight(thief);
    }

    // ------------------------------------------------------------------ ticking

    /** Advances every controller. One bounded map walk per tick, and nothing else when idle. */
    public static void tick(MinecraftServer server) {
        if (server == null || ACTIVE.isEmpty()) {
            return;
        }
        boolean enabled = McaCrimeConfig.COMMON.enableThieves.get();
        ThiefPolicy policy = ThiefPolicy.fromConfig();
        List<UUID> finished = new ArrayList<>();

        for (ThiefBehaviorController controller : ACTIVE.values()) {
            ServerLevel level = levelOf(server, controller.dimension());
            if (level == null) {
                finished.add(controller.thiefId());
                continue;
            }
            long now = level.getGameTime();
            if (!controller.shouldThink(now)) {
                continue;
            }
            controller.scheduleThink(now, THINK_INTERVAL_TICKS);

            Entity entity = level.getEntity(controller.thiefId());
            if (!(entity instanceof LivingEntity thief) || thief.isRemoved()) {
                // Unloaded or dead. The criminal job lives in world data and is unaffected.
                finished.add(controller.thiefId());
                continue;
            }
            // A canceled death can leave zero health until a revival handler repairs it. Pause;
            // confirmed-death cleanup or entity unload owns removal of this controller.
            if (!thief.isAlive()) continue;
            if (thief.isSleeping()) {
                dev.otectus.mcacrime.ai.NpcAwareness.settleSleeping(thief);
                NpcMuggingService.abort(controller.victimId(), dev.otectus.mcacrime.mug.npc.NpcMugAbortReason.CANCELLED);
                controller.setVictim(null);
                controller.setTransactionId(null);
                if (controller.state() != ThiefState.ARRESTED) controller.enter(ThiefState.COOLDOWN, now);
                continue;
            }
            if (!enabled || WorldCriminalJobService.of(server).get(thief.getUUID()) != CriminalJob.THIEF) {
                // Switched off mid-session: stop cleanly rather than freezing a thief mid-approach.
                ThiefTicker.stop(controller.thiefId());
                finished.add(controller.thiefId());
                continue;
            }
            if (!think(server, level, controller, thief, policy, now)) {
                finished.add(controller.thiefId());
            }
        }

        for (UUID thief : finished) {
            untrack(thief);
        }
    }

    /** @return false when this thief should stop being driven. */
    private static boolean think(MinecraftServer server, ServerLevel level, ThiefBehaviorController controller,
                                 LivingEntity thief, ThiefPolicy policy, long now) {
        if (reconcileCustody(controller, CrimeWorldData.get(server).isCaptive(thief.getUUID()),
                now, policy.mugCooldownTicks())) return true;
        ServerPlayer victim = victimOf(server, level, controller);
        if (controller.shouldEvaluateRisk(now) && controller.state() != ThiefState.COOLDOWN) {
            controller.scheduleRisk(now, RISK_INTERVAL_TICKS);
            evaluateRisk(level, thief, controller, policy);
        }

        ThiefState previous = controller.state();
        ThiefStateMachine.ThiefSignals signals = signals(server, level, controller, thief, victim, policy, now);
        if (previous == ThiefState.SCOUTING && signals.victimInvalid()) {
            // A relationship/config change between scan and approach must also release the reservation.
            controller.setVictim(null);
        }
        ThiefState next = ThiefStateMachine.next(previous, signals);
        if (next != previous) {
            controller.enter(next, now);
            onEnter(level, controller, thief, victim, policy, now);
        }

        return switch (controller.state()) {
            case IDLE, SCOUTING -> tickScouting(server, level, controller, thief, policy, now);
            case APPROACHING -> tickApproaching(controller, thief, victim);
            case THREATENING, MUGGING -> tickThreatening(controller, thief, victim);
            case FLEEING -> tickFleeing(controller, thief, now);
            case COOLDOWN -> true;
            case ARRESTED -> true;
            case DEAD -> false;
        };
    }

    /** Custody survives entity/controller reloads. A missed release callback recovers into cooldown. */
    public static boolean reconcileCustody(ThiefBehaviorController controller, boolean held,
                                            long now, long cooldownTicks) {
        if (held) {
            controller.consumeGuardIntervention();
            controller.setVictim(null);
            controller.setTransactionId(null);
            controller.enter(ThiefState.ARRESTED, now);
        } else if (controller.state() == ThiefState.ARRESTED) {
            controller.consumeGuardIntervention();
            controller.enter(ThiefState.COOLDOWN, now);
            controller.setCooldownUntil(now + Math.max(0L, cooldownTicks));
        }
        return held;
    }

    /** Every fact the pure state machine is allowed to see, read once. */
    private static ThiefStateMachine.ThiefSignals signals(MinecraftServer server, ServerLevel level,
                                                          ThiefBehaviorController controller, LivingEntity thief,
                                                          @Nullable ServerPlayer victim, ThiefPolicy policy,
                                                          long now) {
        boolean victimInvalid = controller.victimId() != null
                && (victim == null || !NpcMuggingService.canTarget(level, thief, victim));
        boolean victimFound = victim != null && !victimInvalid;
        boolean inReach = victim != null && thief.distanceToSqr(victim) <= THREAT_REACH * THREAT_REACH;
        boolean victimArmed = victim != null && WeaponDetector.isArmed(victim);
        Optional<NpcMugSession> session = NpcMuggingService.sessionForThief(controller.thiefId());
        boolean timerDone = switch (controller.state()) {
            // The threat is over the moment the session exists; the session's own timer runs the mug.
            case THREATENING -> session.isPresent();
            case MUGGING -> session.isEmpty();
            default -> false;
        };
        boolean escaped = controller.state() == ThiefState.FLEEING
                && (controller.ticksInState(now) >= FLEE_TIMEOUT_TICKS
                || (controller.fleeTarget() != null && McaCompat.navigationDone(thief)));
        boolean cooldownOver = now >= controller.cooldownUntil();
        return new ThiefStateMachine.ThiefSignals(victimFound, inReach,
                controller.guardRisk().exceeds(policy.guardRiskAbortThreshold()), victimArmed, victimInvalid,
                timerDone, controller.consumeGuardIntervention(), !thief.isAlive(), cooldownOver, escaped);
    }

    /** Entry work for a state the controller has only just moved into. */
    private static void onEnter(ServerLevel level, ThiefBehaviorController controller, LivingEntity thief,
                                @Nullable ServerPlayer victim, ThiefPolicy policy, long now) {
        switch (controller.state()) {
            case THREATENING -> {
                if (victim == null) {
                    return;
                }
                McaCompat.stopModNavigation(thief);
                McaCompat.faceEntity(thief, victim);
                Optional<NpcMugSession> session = NpcMuggingService.begin(level, thief, victim);
                if (session.isEmpty()) {
                    // Somebody else already has them, or a listener said no. Nothing was said and
                    // nothing was drawn, so this is a failed approach rather than an aborted crime.
                    controller.noteFailure();
                    controller.setVictim(null);
                    controller.enter(ThiefState.SCOUTING, now);
                    return;
                }
                controller.setTransactionId(session.get().transactionId());
            }
            case FLEEING -> {
                controller.setFleeTarget(fleePoint(controller, thief, victim));
                Vec3 target = controller.fleeTarget();
                if (target != null) {
                    McaCompat.moveVillagerTo(thief, target.x, target.y, target.z, FLEE_SPEED);
                }
            }
            case COOLDOWN -> {
                controller.setVictim(null);
                controller.setTransactionId(null);
                controller.setCooldownUntil(now + policy.mugCooldownTicks());
                McaCompat.releaseVillagerControl(thief);
            }
            case ARRESTED -> {
                controller.setVictim(null);
                controller.setTransactionId(null);
                McaCompat.stopModNavigation(thief);
            }
            case SCOUTING -> {
                controller.setVictim(null);
                controller.setTransactionId(null);
                McaCompat.releaseVillagerControl(thief);
            }
            default -> {
            }
        }
    }

    private static boolean tickScouting(MinecraftServer server, ServerLevel level,
                                        ThiefBehaviorController controller, LivingEntity thief,
                                        ThiefPolicy policy, long now) {
        if (!controller.shouldScan(now)) {
            return true;
        }
        controller.scheduleScan(now, jitter(level, policy.scanIntervalTicks()));
        if (onMugCooldown(server, controller, policy, now)) {
            return true;
        }
        scan(level, controller, thief, policy);
        return true;
    }

    private static boolean tickApproaching(ThiefBehaviorController controller, LivingEntity thief,
                                           @Nullable ServerPlayer victim) {
        if (victim == null) {
            return true;
        }
        // Re-issued every think rather than once on entry: MCA's brain re-paths on its own schedule
        // and would walk the thief back to work halfway across the square.
        McaCompat.moveVillagerTo(thief, victim.getX(), victim.getY(), victim.getZ(), APPROACH_SPEED);
        return true;
    }

    private static boolean tickThreatening(ThiefBehaviorController controller, LivingEntity thief,
                                           @Nullable ServerPlayer victim) {
        if (victim != null) {
            McaCompat.stopModNavigation(thief);
            McaCompat.faceEntity(thief, victim);
        }
        return true;
    }

    private static boolean tickFleeing(ThiefBehaviorController controller, LivingEntity thief, long now) {
        if (controller.fleeTarget() == null) {
            controller.setFleeTarget(fleePoint(controller, thief, null));
        }
        Vec3 target = controller.fleeTarget();
        if (target != null) {
            // Re-issued each think for the same reason the approach is: MCA re-paths underneath us.
            McaCompat.moveVillagerTo(thief, target.x, target.y, target.z, FLEE_SPEED);
        }
        return true;
    }

    // ------------------------------------------------------------------ world queries

    /**
     * One bounded scan: the guards that can see this thief, and the players worth robbing.
     *
     * <p>Runs on the jittered scan interval, never per tick, and reads each player's currency exactly
     * once — spec §"Victim selection" forbids walking an inventory to estimate wealth.
     */
    private static void scan(ServerLevel level, ThiefBehaviorController controller, LivingEntity thief,
                             ThiefPolicy policy) {
        AABB box = thief.getBoundingBox().inflate(policy.targetSearchRadius());
        List<ServerPlayer> nearby = level.getEntitiesOfClass(ServerPlayer.class, box,
                player -> player.isAlive() && !player.isSpectator());
        if (nearby.isEmpty()) {
            return;
        }

        List<MugTargetSelector.VictimCandidate> candidates = new ArrayList<>(nearby.size());
        for (ServerPlayer player : nearby) {
            // Apply the same protections as the live session before reading a potential victim's wealth.
            if (!NpcMuggingService.canTarget(level, thief, player)) continue;
            int crowd = 0;
            for (ServerPlayer other : nearby) {
                if (other != player && other.distanceToSqr(player) <= CROWD_RADIUS * CROWD_RADIUS) {
                    crowd++;
                }
            }
            candidates.add(new MugTargetSelector.VictimCandidate(player.getUUID(),
                    Math.sqrt(thief.distanceToSqr(player)),
                    WeaponDetector.isArmed(player),
                    player.isCreative() || player.isSpectator(),
                    player.isInvulnerable(),
                    NpcMuggingService.isVictim(player.getUUID()) || targetedByAnother(controller, player.getUUID()),
                    EntitySelectors.isProtected(player),
                    thief.hasLineOfSight(player),
                    crowd,
                    Currencies.active().balance(player),
                    controller.failures()));
        }

        Optional<MugTargetSelector.Scored> chosen =
                MugTargetSelector.select(candidates, controller.guardRisk(), policy);
        if (chosen.isEmpty()) {
            return;
        }
        controller.setVictim(chosen.get().id());
        NeoForge.EVENT_BUS.post(new CrimeIntentEvent(controller.thiefId(), chosen.get().id(),
                CrimeIds.MUGGING, level.dimension().location()));
        CrimeDebug.crime("thief {} selected victim {} score {} (guard risk {})", controller.thiefId(),
                chosen.get().id(), String.format(java.util.Locale.ROOT, "%.2f", chosen.get().score()),
                String.format(java.util.Locale.ROOT, "%.2f", controller.guardRisk().riskScore()));
    }

    /** Caches how exposed the thief is, and where the nearest guard was when it looked. */
    private static void evaluateRisk(ServerLevel level, LivingEntity thief, ThiefBehaviorController controller,
                                     ThiefPolicy policy) {
        AABB box = thief.getBoundingBox().inflate(policy.guardAvoidRadius());
        List<GuardRiskEvaluator.GuardSighting> sightings = new ArrayList<>();
        Vec3 nearestPosition = null;
        double nearest = Double.MAX_VALUE;
        for (LivingEntity responder : level.getEntitiesOfClass(LivingEntity.class, box,
                entity -> entity != thief && EntitySelectors.isAvailableResponder(entity))) {
            double distance = Math.sqrt(thief.distanceToSqr(responder));
            sightings.add(new GuardRiskEvaluator.GuardSighting(distance, responder.hasLineOfSight(thief)));
            if (distance < nearest) {
                nearest = distance;
                nearestPosition = responder.position();
            }
        }
        controller.setGuardRisk(GuardRiskEvaluator.evaluate(sightings, policy.guardAvoidRadius(),
                policy.guardHardAbortRadius()), nearestPosition);
    }

    /**
     * A point on the ray away from the guard the thief last saw, or away from the victim when it has
     * seen none. Spec §"Thieves must avoid guards" rules out solving a path against every guard, so
     * this is one direction and one destination, re-picked only when the last one is reached.
     */
    @Nullable
    private static Vec3 fleePoint(ThiefBehaviorController controller, LivingEntity thief,
                                  @Nullable ServerPlayer victim) {
        Vec3 from = controller.lastGuardPosition() != null ? controller.lastGuardPosition()
                : victim != null ? victim.position() : null;
        Vec3 origin = thief.position();
        Vec3 away = from == null ? null : origin.subtract(from);
        if (away == null || away.horizontalDistanceSqr() < 1.0E-4D) {
            // Nowhere in particular to run from: keep the direction the thief is already facing.
            away = Vec3.directionFromRotation(0.0F, thief.getYRot());
        }
        Vec3 direction = new Vec3(away.x, 0.0D, away.z).normalize();
        return origin.add(direction.scale(FLEE_DISTANCE));
    }

    // ------------------------------------------------------------------ helpers

    /** Whether the thief's own mug cooldown is still running. Stamped when a session ends. */
    private static boolean onMugCooldown(MinecraftServer server, ThiefBehaviorController controller,
                                         ThiefPolicy policy, long now) {
        CriminalVillagerRecord record = WorldCriminalJobService.of(server).record(controller.thiefId()).orElse(null);
        return record != null && record.lastMugAt() > 0L
                && now - record.lastMugAt() < policy.mugCooldownTicks();
    }

    private static boolean targetedByAnother(ThiefBehaviorController controller, UUID player) {
        for (ThiefBehaviorController other : ACTIVE.values()) {
            if (other != controller && player.equals(other.victimId())) {
                return true;
            }
        }
        return false;
    }

    @Nullable
    private static ServerPlayer victimOf(MinecraftServer server, ServerLevel level,
                                         ThiefBehaviorController controller) {
        if (controller.victimId() == null) {
            return null;
        }
        ServerPlayer victim = server.getPlayerList().getPlayer(controller.victimId());
        return victim != null && victim.isAlive() && victim.level() == level ? victim : null;
    }

    /** The configured interval, spread by a quarter either way so thieves never scan in lockstep. */
    private static int jitter(ServerLevel level, int interval) {
        int spread = Math.max(1, interval / 4);
        return Math.max(1, interval - spread + level.getRandom().nextInt(2 * spread + 1));
    }

    private static long gameTime(ThiefBehaviorController controller) {
        MinecraftServer server = net.neoforged.neoforge.server.ServerLifecycleHooks.getCurrentServer();
        ServerLevel level = levelOf(server, controller.dimension());
        return level == null ? controller.stateEnteredAt() : level.getGameTime();
    }

    @Nullable
    private static ServerLevel levelOf(@Nullable MinecraftServer server, ResourceLocation dimension) {
        return server == null ? null : server.getLevel(ResourceKey.create(Registries.DIMENSION, dimension));
    }
}
