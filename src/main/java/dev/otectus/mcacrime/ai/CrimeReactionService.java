package dev.otectus.mcacrime.ai;

import dev.otectus.mcacrime.McaCrimeConfig;
import dev.otectus.mcacrime.action.ActionSession;
import dev.otectus.mcacrime.action.ActionSessionManager;
import dev.otectus.mcacrime.api.event.WitnessReactionChangedEvent;
import dev.otectus.mcacrime.compat.McaCompat;
import dev.otectus.mcacrime.enforcement.LawHold;
import dev.otectus.mcacrime.detect.EntitySelectors;
import dev.otectus.mcacrime.dialogue.CrimeDialogueService;
import dev.otectus.mcacrime.dialogue.DialogueEvents;
import dev.otectus.mcacrime.memory.CrimeObservation;
import dev.otectus.mcacrime.memory.OffenderMemory;
import dev.otectus.mcacrime.memory.ReportService;
import dev.otectus.mcacrime.memory.VillagerCrimeProfile;
import dev.otectus.mcacrime.state.world.CrimeWorldData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.common.NeoForge;

import org.jetbrains.annotations.Nullable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Owns every active villager reaction (spec §11.2/§11.3).
 *
 * <p>The performance shape is the important part. There is no scan of villagers anywhere in this
 * class: reactions are created by an event (an observation, a capture, a threat) and the ticker only
 * walks the controllers that already exist. A world with three hundred villagers and one mugging in
 * progress does exactly one villager's worth of work, and the {@code maxActiveReactions} ceiling means
 * a village-wide panic is bounded rather than proportional to the crowd.
 *
 * <p>The MCA-coexistence rules from §11.3 are enforced here rather than trusted to callers. Navigation
 * is reissued on a bounded interval and only in states that declare {@link
 * VictimReactionState#ownsNavigation()}; every exit path runs {@link #release}, which stops the path
 * and clears the target so MCA's brain recomputes normal activity instead of inheriting a stale one.
 */
public final class CrimeReactionService {

    /** villager id -> live reaction. Memory-only: a reaction lasts seconds and must not persist. */
    private static final Map<UUID, ActiveCrimeReactionController> ACTIVE = new ConcurrentHashMap<>();

    /** How long the REPORTING hand-off takes once the villager has reached a responder. */
    private static final int REPORT_DELIVERY_TICKS = 40;
    /** How close the villager must be to a responder to start delivering. */
    private static final double REPORT_REACH = 3.0;
    /** Path failures tolerated before a state gives up and falls back. */
    private static final int MAX_PATH_FAILURES = 3;
    /** Beat before a THREATENED villager commits to a response, so the decision is legible. */
    private static final int DECISION_DELAY_TICKS = 10;
    /**
     * Think cadence for a frozen victim. Every tick, and deliberately so: MCA's brain re-paths on its
     * own schedule, so anything slower let a mugged villager drift a step at a time out of the mugging
     * between reassertions. The cost is one villager per live coercive session, which is nothing.
     */
    private static final int COMPLYING_THINK_TICKS = 1;

    static {
        // The only way this layer learns how a coercive session ended. Registered at class init, which
        // is reached long before any session can exist: a reaction has to be triggered before there is
        // anything for a listener to say.
        ActionSessionManager.addEndListener((session, reason) -> {
            ActiveCrimeReactionController controller = ACTIVE.get(session.targetId());
            if (controller == null || !session.sessionId().equals(controller.coerciveSessionId())) {
                return;
            }
            controller.noteSessionEnded(reason == null
                    ? ActiveCrimeReactionController.SessionOutcome.FINISHED
                    : ActiveCrimeReactionController.SessionOutcome.CANCELLED);
        });
    }

    private CrimeReactionService() {
    }

    // ------------------------------------------------------------------ lifecycle

    /**
     * Starts or retargets a reaction.
     *
     * @return the controller, or null when reactions are disabled, the entity cannot react, or the
     *         active ceiling is already reached. A refused reaction is not an error: the villager
     *         simply keeps behaving as MCA would have it behave.
     */
    @Nullable
    public static ActiveCrimeReactionController trigger(ServerLevel level, LivingEntity villager,
                                                        @Nullable UUID offender, VictimReactionState initial,
                                                        @Nullable UUID observationId) {
        if (!McaCrimeConfig.COMMON.enableVillagerReactions.get() || level == null || villager == null) {
            return null;
        }
        if (!McaCompat.isMcaVillager(villager) || !NpcAwareness.isAwake(villager)) {
            return null;
        }
        if (initial != VictimReactionState.CAPTIVE && dev.otectus.mcacrime.state.world.CrimeWorldData
                .get(level.getServer()).isCaptive(villager.getUUID())) return null;
        if (!ReactionControlPolicy.mayControl(EntitySelectors.isResponder(villager),
                LawHold.isHeld(villager.getUUID(), level.getGameTime()), initial)) {
            // Law roles retain enforcement/combat AI, even while injured or facing an armed suspect.
            // A civilian fear controller must not take over their route or clear their combat target.
            return null;
        }
        ActiveCrimeReactionController existing = ACTIVE.get(villager.getUUID());
        if (existing != null) {
            // A second offender takes over an already-panicking villager rather than queueing behind
            // the first: whoever is threatening them right now is what they are reacting to.
            existing.retarget(offender);
            existing.carryObservation(observationId);
            transition(level, existing, initial, durationOf(initial));
            return existing;
        }
        if (ACTIVE.size() >= McaCrimeConfig.COMMON.maxActiveReactions.get()) {
            return null;
        }
        ActiveCrimeReactionController controller = new ActiveCrimeReactionController(villager.getUUID(),
                level.dimension().location(), offender, observationId, level.getGameTime());
        ACTIVE.put(villager.getUUID(), controller);
        transition(level, controller, initial, durationOf(initial));
        return controller;
    }

    /** The villager's current state. {@link VictimReactionState#CALM} when no controller exists. */
    public static VictimReactionState stateOf(UUID villager) {
        ActiveCrimeReactionController controller = ACTIVE.get(villager);
        return controller == null ? VictimReactionState.CALM : controller.state();
    }

    /** Whether this villager is currently refusing or altering interaction with this offender. */
    public static boolean isAvoiding(UUID villager, UUID offender) {
        ActiveCrimeReactionController controller = ACTIVE.get(villager);
        return controller != null && controller.state() != VictimReactionState.CALM
                && offender != null && offender.equals(controller.offenderId());
    }

    public static int activeCount() {
        return ACTIVE.size();
    }

    /** Ends a reaction and hands the villager back to MCA. Safe to call for an unknown villager. */
    public static void clear(@Nullable ServerLevel level, UUID villager) {
        ActiveCrimeReactionController controller = ACTIVE.remove(villager);
        if (controller == null) {
            return;
        }
        if (level != null) {
            release(level, villager);
        }
        dev.otectus.mcacrime.incident.IncidentNotifications.post(new WitnessReactionChangedEvent(villager, controller.offenderId(),
                controller.state(), VictimReactionState.CALM));
    }

    /** Drops every reaction. Called on server stop so a restart never inherits stale controllers. */
    public static void clearAll() {
        clearAll(null);
    }

    /**
     * As {@link #clearAll()}, but with the server available so every controlled villager also gets its
     * speed modifier taken off. The modifier is transient and cannot be saved, so this only matters for
     * a server that keeps running (a single-player world being left, an integrated server restarting);
     * with no server there is nothing to release and the map is simply dropped.
     */
    public static void clearAll(@Nullable MinecraftServer server) {
        if (server != null) {
            for (ActiveCrimeReactionController controller : ACTIVE.values()) {
                ServerLevel level = levelOf(server, controller.dimension());
                if (level != null) {
                    release(level, controller.villagerId());
                }
            }
        }
        ACTIVE.clear();
    }

    // ------------------------------------------------------------------ ticking

    /**
     * Advances every active reaction. Called once per server tick; each controller decides for itself
     * whether this is one of its think ticks, so the per-tick cost is one map walk over a bounded set
     * and nothing else.
     */
    public static void tick(MinecraftServer server) {
        if (ACTIVE.isEmpty() || server == null) {
            return;
        }
        if (!McaCrimeConfig.COMMON.enableVillagerReactions.get()
                || !dev.otectus.mcacrime.state.world.ServerMutationGate.allows(server)) {
            clearAll(server);
            return;
        }
        int thinkInterval = McaCrimeConfig.COMMON.reactionTickIntervalTicks.get();
        List<UUID> finished = new ArrayList<>();

        for (ActiveCrimeReactionController controller : ACTIVE.values()) {
            ServerLevel level = levelOf(server, controller.dimension());
            if (level == null) {
                finished.add(controller.villagerId());
                continue;
            }
            long now = level.getGameTime();
            if (!controller.shouldThink(now)) {
                continue;
            }
            controller.scheduleThink(now, controller.state() == VictimReactionState.COMPLYING
                    ? Math.min(thinkInterval, COMPLYING_THINK_TICKS)
                    : thinkInterval);

            Entity entity = level.getEntity(controller.villagerId());
            if (!(entity instanceof LivingEntity villager) || villager.isRemoved()) {
                // Unloaded or dead. Either way the controller has nothing to drive; memory of the
                // offender lives in world data and is unaffected.
                finished.add(controller.villagerId());
                continue;
            }
            if (!villager.isAlive()) continue; // Await confirmed death or revival without forgetting the reaction.
            if (villager.isSleeping()) {
                NpcAwareness.settleSleeping(villager);
                finished.add(controller.villagerId());
                continue;
            }
            if (!ReactionControlPolicy.mayControl(EntitySelectors.isResponder(villager),
                    LawHold.isHeld(villager.getUUID(), now), controller.state())) {
                finished.add(controller.villagerId());
                continue;
            }
            if (!think(level, controller, villager, now)) {
                finished.add(controller.villagerId());
            }
        }

        for (UUID villager : finished) {
            ActiveCrimeReactionController controller = ACTIVE.remove(villager);
            if (controller != null) {
                ServerLevel level = levelOf(server, controller.dimension());
                if (level != null) {
                    release(level, villager);
                }
                dev.otectus.mcacrime.incident.IncidentNotifications.post(new WitnessReactionChangedEvent(villager,
                        controller.offenderId(), controller.state(), VictimReactionState.CALM));
            }
        }
    }

    /** @return false when the reaction is over and the controller should be dropped. */
    private static boolean think(ServerLevel level, ActiveCrimeReactionController controller,
                                 LivingEntity villager, long now) {
        ServerPlayer offender = offenderOf(level, controller);
        return switch (controller.state()) {
            case THREATENED -> tickThreatened(level, controller, villager, offender, now);
            case COMPLYING -> tickComplying(level, controller, villager, offender, now);
            case RESISTING -> tickResisting(level, controller, villager, offender, now);
            case FLEEING, PANICKING -> tickFleeing(level, controller, villager, offender, now);
            case STALLING, DEFYING -> tickDeliberating(level, controller, villager, offender, now);
            case SEEKING_HELP -> tickSeekingHelp(level, controller, villager, offender, now);
            case REPORTING -> tickReporting(level, controller, villager, now);
            case HIDING -> tickHiding(level, controller, villager, offender, now);
            case RECOVERING -> !controller.timedOut(now);
            // Captivity is ended by the custody system, never by a timeout in here.
            case CAPTIVE -> true;
            case CALM -> false;
        };
    }

    private static boolean tickThreatened(ServerLevel level, ActiveCrimeReactionController controller,
                                          LivingEntity villager, @Nullable ServerPlayer offender, long now) {
        if (offender == null) {
            return transition(level, controller, VictimReactionState.RECOVERING,
                    durationOf(VictimReactionState.RECOVERING));
        }
        McaCompat.faceEntity(villager, offender);

        // Freezing is a reaction to being robbed, not to being near somebody armed: it takes a live
        // coercive session naming this villager as its target, which is what this lookup is. It is read
        // before the deliberation beat because it is also what removes the beat.
        Optional<ActionSession> coercive = ActionSessionManager.activeCoerciveAgainst(controller.villagerId());
        if (controller.ticksInState(now) < decisionDelayFor(coercive.isPresent()) && !controller.timedOut(now)) {
            return true;
        }
        McaCrimeConfig.Common c = McaCrimeConfig.COMMON;
        ReactionFactors factors = factorsFor(level, villager, offender);
        boolean helpNearby = nearestResponder(level, villager, controller.offenderId()) != null;
        ArmedResolver.ArmedStatus armed = ArmedResolver.classify(villager);
        // Only an unarmed villager is ever slowed. A guard who is about to swing does not shuffle.
        controller.setCivilian(!armed.armed());
        if (c.enableDynamicCompliance.get()) {
            return decideDynamic(level, controller, villager, offender, now, coercive.isPresent());
        }

        // Order matters: fighting is a choice about this moment, fetching a guard is a choice about
        // what happens next, and running is what is left. Checking them the other way round would make
        // every brave villager walk off to find help mid-robbery.
        ThreatComplianceDecider.Decision decision = ThreatComplianceDecider.decide(armed, coercive.isPresent(),
                factors.resistanceScore(), factors.helpSeekingScore(), helpNearby,
                c.armedVillagersCanResist.get(), c.freezeComplyingVictims.get(),
                c.complianceResistThreshold.get(), c.complianceHelpThreshold.get());

        switch (decision) {
            case RESIST -> {
                // A guard resisting is an arrest starting, so the enforcement lever goes on too. A
                // civilian resisting is a shove, and tickResisting is all that one needs.
                if (McaCompat.isGuard(villager)) {
                    McaCompat.setGuardTarget(villager, offender);
                }
                return transition(level, controller, VictimReactionState.RESISTING,
                        durationOf(VictimReactionState.RESISTING));
            }
            case COMPLY -> {
                controller.markCoerced(coercive.map(ActionSession::sessionId).orElse(null));
                boolean alive = transition(level, controller, VictimReactionState.COMPLYING,
                        durationOf(VictimReactionState.COMPLYING));
                // The hold goes on during the same think the decision is made. Waiting for the first
                // COMPLYING think would leave the victim free to take another step first, which is the
                // whole complaint.
                holdComplying(villager, offender);
                return alive;
            }
            case SEEK_HELP -> {
                return transition(level, controller, VictimReactionState.SEEKING_HELP,
                        durationOf(VictimReactionState.SEEKING_HELP));
            }
            default -> {
                return transition(level, controller, VictimReactionState.FLEEING,
                        durationOf(VictimReactionState.FLEEING));
            }
        }
    }

    /**
     * Holds a complying villager still for as long as the session that froze them lives, then routes
     * them out by how it ended.
     *
     * <p>Both levers are re-applied every think rather than once on entry. MCA's brain re-paths on its
     * own schedule and would walk the villager out of the mugging within a second of a single stop;
     * re-asserting is cheaper than fighting the result.
     */
    private static boolean tickComplying(ServerLevel level, ActiveCrimeReactionController controller,
                                         LivingEntity villager, @Nullable ServerPlayer offender, long now) {
        Optional<ActionSession> coercive = ActionSessionManager.activeCoerciveAgainst(controller.villagerId());
        if (coercive.isPresent()) {
            if (offender == null) return transition(level, controller, VictimReactionState.RECOVERING, durationOf(VictimReactionState.RECOVERING));
            if (McaCrimeConfig.COMMON.enableDynamicCompliance.get() && controller.shouldEvaluateThreat(now)) {
                if (offender == null) return transition(level, controller, VictimReactionState.FLEEING, durationOf(VictimReactionState.FLEEING));
                decideDynamic(level, controller, villager, offender, now, true);
                if (controller.state() != VictimReactionState.COMPLYING) return true;
            }
            holdComplying(villager, offender);
            // No timeout while somebody is still holding them: the session owns this state's lifetime.
            return true;
        }
        if (controller.lastSessionOutcome() == ActiveCrimeReactionController.SessionOutcome.CANCELLED) {
            // Broken off rather than completed. Whoever was frozen a tick ago is now free, and the
            // nearest guard is about to hear about it.
            LivingEntity responder = nearestResponder(level, villager, controller.offenderId());
            if (responder == null) {
                return transition(level, controller, VictimReactionState.FLEEING,
                        durationOf(VictimReactionState.FLEEING));
            }
            if (villager.distanceToSqr(responder) <= REPORT_REACH * REPORT_REACH) {
                return transition(level, controller, VictimReactionState.REPORTING, REPORT_DELIVERY_TICKS);
            }
            return transition(level, controller, VictimReactionState.SEEKING_HELP,
                    durationOf(VictimReactionState.SEEKING_HELP));
        }
        // Finished, or frozen by something that is no longer there. Either way it is over.
        return transition(level, controller, VictimReactionState.RECOVERING,
                durationOf(VictimReactionState.RECOVERING));
    }

    /**
     * How long a THREATENED villager deliberates before committing (pure, so it can be asserted).
     *
     * <p>The beat exists to make an ambiguous observation legible: somebody saw something, hesitated,
     * then reacted. A live coercive session is not ambiguous — it is a named offender with an open
     * action against this exact villager — and ten ticks of hesitation there is half a second of the
     * victim walking out of their own mugging before anything holds them.
     */
    public static int decisionDelayFor(boolean coerciveSessionActive) {
        return coerciveSessionActive ? 0 : DECISION_DELAY_TICKS;
    }

    /**
     * Everything that keeps a complying victim where they are, applied together so the decision think
     * and the COMPLYING think cannot drift apart.
     *
     * <p>Three levers, because each covers what the others miss: {@link McaCompat#holdPosition} erases
     * the brain's walk memories and kills momentum, the speed modifier makes any movement that is
     * nonetheless issued cover no ground, and looking at the offender keeps the freeze legible rather
     * than reading as a bugged villager staring at a wall. The modifier is the transient, fixed-id one
     * every exit path already removes, so it cannot outlive the hold.
     */
    private static void holdComplying(LivingEntity villager, @Nullable ServerPlayer offender) {
        if (offender != null) {
            McaCompat.faceEntity(villager, offender);
        }
        McaCompat.stopModNavigation(villager);
        if (McaCrimeConfig.COMMON.freezeComplyingVictims.get()) {
            McaCompat.holdPosition(villager);
            ReactionSpeedModifier.apply(villager, 0.0D);
        }
    }

    private static boolean tickResisting(ServerLevel level, ActiveCrimeReactionController controller,
                                         LivingEntity villager, @Nullable ServerPlayer offender, long now) {
        if (offender == null || villager.distanceToSqr(offender) > 100.0D) {
            return transition(level, controller, VictimReactionState.RECOVERING,
                    durationOf(VictimReactionState.RECOVERING));
        }
        McaCompat.makeVillagerResist(villager, offender);
        if (controller.timedOut(now)) {
            return transition(level, controller, VictimReactionState.FLEEING,
                    durationOf(VictimReactionState.FLEEING));
        }
        return true;
    }

    private static boolean tickFleeing(ServerLevel level, ActiveCrimeReactionController controller,
                                       LivingEntity villager, @Nullable ServerPlayer offender, long now) {
        if (controller.timedOut(now) || controller.pathFailures() >= MAX_PATH_FAILURES) {
            return transition(level, controller, VictimReactionState.HIDING,
                    durationOf(VictimReactionState.HIDING));
        }
        if (controller.destination() != null && villager.distanceToSqr(
                net.minecraft.world.phys.Vec3.atBottomCenterOf(controller.destination())) <= 4D) {
            return transition(level, controller, VictimReactionState.HIDING,
                    durationOf(VictimReactionState.HIDING));
        }
        repath(level, controller, villager, offender, now, false);
        return true;
    }

    private static boolean tickSeekingHelp(ServerLevel level, ActiveCrimeReactionController controller,
                                           LivingEntity villager, @Nullable ServerPlayer offender, long now) {
        LivingEntity responder = nearestResponder(level, villager, controller.offenderId());
        if (responder == null || controller.pathFailures() >= MAX_PATH_FAILURES) {
            // Nobody to tell. The observation stays pending — §12.3 is explicit that an undeliverable
            // report is retained rather than discarded, so it can still expire or be gossiped later.
            return transition(level, controller, VictimReactionState.FLEEING,
                    durationOf(VictimReactionState.FLEEING));
        }
        if (villager.distanceToSqr(responder) <= REPORT_REACH * REPORT_REACH) {
            return transition(level, controller, VictimReactionState.REPORTING, REPORT_DELIVERY_TICKS);
        }
        if (controller.timedOut(now)) {
            return transition(level, controller, VictimReactionState.FLEEING,
                    durationOf(VictimReactionState.FLEEING));
        }
        if (controller.shouldRepath(now)) {
            controller.scheduleRepath(now, McaCrimeConfig.COMMON.reactionNavigationIntervalTicks.get());
            BlockPos target = responder.blockPosition();
            if (McaCompat.moveVillagerTo(villager, target.getX() + 0.5, target.getY(), target.getZ() + 0.5, 1.1)) {
                controller.setDestination(target);
                controller.clearPathFailures();
            } else {
                controller.notePathFailure();
            }
        }
        return true;
    }

    private static boolean tickReporting(ServerLevel level, ActiveCrimeReactionController controller,
                                         LivingEntity villager, long now) {
        if (!controller.reported()) {
            LivingEntity responder = nearestResponder(level, villager, controller.offenderId());
            if (responder == null || villager.distanceToSqr(responder) > REPORT_REACH * REPORT_REACH
                    || !villager.hasLineOfSight(responder)) {
                return transition(level, controller, VictimReactionState.SEEKING_HELP, durationOf(VictimReactionState.SEEKING_HELP));
            }
            if (ReportService.deliver(level, villager, responder, controller.observationId()).isPresent()) controller.markReported();
        }
        if (controller.timedOut(now)) {
            return transition(level, controller, VictimReactionState.RECOVERING,
                    durationOf(VictimReactionState.RECOVERING));
        }
        return true;
    }

    private static boolean tickHiding(ServerLevel level, ActiveCrimeReactionController controller,
                                      LivingEntity villager, @Nullable ServerPlayer offender, long now) {
        if (offender != null && villager.distanceToSqr(offender) < 36.0D) {
            // They found us. Run again rather than standing in a corner being robbed a second time.
            return transition(level, controller, VictimReactionState.FLEEING,
                    durationOf(VictimReactionState.FLEEING));
        }
        if (controller.timedOut(now)) {
            return transition(level, controller, VictimReactionState.RECOVERING,
                    durationOf(VictimReactionState.RECOVERING));
        }
        if (controller.destination() == null) {
            repath(level, controller, villager, offender, now, true);
        }
        return true;
    }

    // ------------------------------------------------------------------ navigation

    /**
     * Picks and issues a destination. Candidates are scored first and only then pathed, trying at most
     * three, so a fleeing villager never triggers an unbounded number of path computations in one tick.
     */
    private static void repath(ServerLevel level, ActiveCrimeReactionController controller,
                               LivingEntity villager, @Nullable ServerPlayer offender, long now,
                               boolean preferShelter) {
        if (!controller.shouldRepath(now)) {
            return;
        }
        // Keep a successful route while it still leads away from danger; choosing a new point every
        // half-second makes a frightened villager zigzag around the same patch of ground.
        if (controller.destination() != null && !McaCompat.navigationDone(villager)
                && (offender == null || controller.destination().distSqr(offender.blockPosition())
                > villager.blockPosition().distSqr(offender.blockPosition()))) return;
        controller.scheduleRepath(now, McaCrimeConfig.COMMON.reactionNavigationIntervalTicks.get());
        List<SafeDestinationSelector.Candidate> candidates =
                sampleDestinations(level, villager, offender, preferShelter);
        if (candidates.isEmpty()) {
            controller.notePathFailure();
            return;
        }
        candidates.sort(Comparator.comparingDouble(SafeDestinationSelector::score).reversed());
        int attempts = 0;
        for (SafeDestinationSelector.Candidate candidate : candidates) {
            if (attempts++ >= 3) {
                break;
            }
            BlockPos pos = candidate.position();
            if (McaCompat.moveVillagerTo(villager, pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, 1.25)) {
                controller.setDestination(pos);
                controller.clearPathFailures();
                return;
            }
        }
        controller.notePathFailure();
    }

    /**
     * Builds the bounded candidate list for {@link SafeDestinationSelector} (spec §11.4). Every source
     * here is a lookup this mod already does elsewhere — a nearby-entity scan and a brain memory read —
     * so there is no POI search on the server thread.
     */
    private static List<SafeDestinationSelector.Candidate> sampleDestinations(
            ServerLevel level, LivingEntity villager, @Nullable ServerPlayer offender, boolean preferShelter) {
        List<SafeDestinationSelector.Candidate> candidates = new ArrayList<>();
        BlockPos self = villager.blockPosition();
        BlockPos threat = offender == null ? self : offender.blockPosition();

        LivingEntity responder = nearestResponder(level, villager, offender == null ? null : offender.getUUID());
        if (responder != null) {
            candidates.add(candidate(responder.blockPosition(), SafeDestinationSelector.Kind.RESPONDER, self, threat));
        }
        McaCompat.homePosition(villager).ifPresent(home ->
                candidates.add(candidate(home, SafeDestinationSelector.Kind.HOME, self, threat)));

        LivingEntity ally = nearestAlly(level, villager);
        if (ally != null) {
            candidates.add(candidate(ally.blockPosition(), SafeDestinationSelector.Kind.ALLY, self, threat));
        }

        // Open ground, sampled on a ring away from the threat. Deterministic angles rather than random
        // ones, so the same standoff produces the same escape route every time it happens.
        if (!preferShelter || candidates.isEmpty()) {
            int samples = McaCrimeConfig.COMMON.safeDestinationSamples.get();
            double awayX = self.getX() - threat.getX();
            double awayZ = self.getZ() - threat.getZ();
            double length = Math.sqrt(awayX * awayX + awayZ * awayZ);
            if (length < 1.0E-4) {
                awayX = 1.0;
                awayZ = 0.0;
                length = 1.0;
            }
            awayX /= length;
            awayZ /= length;
            for (int i = 0; i < samples; i++) {
                // Fan out from straight-away, alternating sides, so early samples are the best ones and
                // the three we actually path are not all crowded into one direction.
                double angle = Math.toRadians(((i + 1) / 2) * 25.0 * ((i % 2 == 0) ? 1 : -1));
                double dirX = awayX * Math.cos(angle) - awayZ * Math.sin(angle);
                double dirZ = awayX * Math.sin(angle) + awayZ * Math.cos(angle);
                BlockPos sample = new BlockPos(
                        (int) Math.round(self.getX() + dirX * 12.0),
                        self.getY(),
                        (int) Math.round(self.getZ() + dirZ * 12.0));
                if (!level.isLoaded(sample)) continue;
                candidates.add(candidate(level.getHeightmapPos(
                                net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, sample),
                        SafeDestinationSelector.Kind.OPEN_GROUND, self, threat));
            }
        }
        return candidates;
    }

    private static SafeDestinationSelector.Candidate candidate(BlockPos pos, SafeDestinationSelector.Kind kind,
                                                               BlockPos self, BlockPos threat) {
        return new SafeDestinationSelector.Candidate(pos, kind,
                Math.sqrt(pos.distSqr(threat)), Math.sqrt(pos.distSqr(self)), true, 0.0);
    }

    // ------------------------------------------------------------------ helpers

    /**
     * Applies a transition, speaks the line it owes, and fires the public event.
     *
     * @return always true, so callers can {@code return transition(...)} from a tick method that has
     *         decided the reaction continues in a new state
     */
    private static boolean transition(ServerLevel level, ActiveCrimeReactionController controller,
                                      VictimReactionState next, long duration) {
        VictimReactionState previous = controller.state();
        if (!controller.enter(next, level.getGameTime(), duration)) {
            return true;
        }
        if (!next.ownsBehaviour()) {
            release(level, controller.villagerId());
        }
        ServerPlayer offender = offenderOf(level, controller);
        Entity entity = level.getEntity(controller.villagerId());
        if (entity instanceof LivingEntity subject) {
            // Direct FLEEING/SEEKING_HELP entries do not pass through tickThreatened.
            controller.setCivilian(!ArmedResolver.classify(subject).armed());
            // Applied on entry to the states that steer, taken off on entry to every other state. Doing
            // it here rather than at each call site is what makes "no state can leak the modifier" a
            // property of the transition rather than a rule every future state has to remember.
            if (next.ownsNavigation() && controller.civilian()) {
                ReactionSpeedModifier.apply(subject,
                        McaCrimeConfig.COMMON.civilianCrimeReactionSpeedMultiplier.get());
            } else {
                ReactionSpeedModifier.remove(subject);
            }
        }
        if (offender != null && entity instanceof LivingEntity villager) {
            speakFor(level, villager, offender, controller, next);
        }
        dev.otectus.mcacrime.incident.IncidentNotifications.post(new WitnessReactionChangedEvent(controller.villagerId(),
                controller.offenderId(), previous, next));
        return true;
    }

    /** The one line a state entry owes the offender, if any. Silence is a valid answer for most. */
    private static void speakFor(ServerLevel level, LivingEntity villager, ServerPlayer offender,
                                 ActiveCrimeReactionController controller, VictimReactionState state) {
        ResourceLocation event = switch (state) {
            case COMPLYING -> McaCrimeConfig.COMMON.enablePleading.get() ? dev.otectus.mcacrime.McaCrime.id("threat_plead") : null;
            case STALLING -> dev.otectus.mcacrime.McaCrime.id("threat_stall");
            case DEFYING -> dev.otectus.mcacrime.McaCrime.id("threat_defy");
            case PANICKING -> dev.otectus.mcacrime.McaCrime.id("threat_panic");
            case RESISTING -> DialogueEvents.MUG_RESIST;
            case SEEKING_HELP -> DialogueEvents.MUG_WITNESSED;
            case RECOVERING -> DialogueEvents.MUG_RECOVERY;
            default -> null;
        };
        if (event == null) {
            return;
        }
        UUID encounter = controller.observationId() == null ? controller.villagerId() : controller.observationId();
        CrimeDialogueService.speak(villager, offender, event,
                CrimeDialogueService.context(level, villager, offender, encounter, event));
    }

    /**
     * Stops this mod's navigation and clears its target, handing the villager back to MCA.
     *
     * <p>A villager under a {@link LawHold} keeps its target: it is a responder that took an
     * enforcement target while a reaction was running. Both its target and its route are preserved:
     * stopping either here would interrupt an arrest this controller no longer owns.
     */
    private static void release(ServerLevel level, UUID villagerId) {
        Entity entity = level.getEntity(villagerId);
        if (entity == null) {
            return;
        }
        if (entity instanceof LivingEntity living) {
            // Unconditional: the modifier is invisible and permanent if it is ever left behind, so this
            // never asks whether one was applied.
            ReactionSpeedModifier.remove(living);
        }
        if (LawHold.isHeld(villagerId, level.getGameTime())
                || entity instanceof LivingEntity living && EntitySelectors.isResponder(living)) {
            return;
        }
        McaCompat.releaseVillagerControl(entity);
    }

    private static long durationOf(VictimReactionState state) {
        McaCrimeConfig.Common c = McaCrimeConfig.COMMON;
        return switch (state) {
            case THREATENED -> c.reactionThreatenedTicks.get();
            case COMPLYING, STALLING, DEFYING -> c.reactionThreatenedTicks.get();
            case RESISTING -> c.reactionThreatenedTicks.get() * 2L;
            case FLEEING, PANICKING -> c.reactionFleeTicks.get();
            case SEEKING_HELP -> c.reactionSeekHelpTicks.get();
            case REPORTING -> REPORT_DELIVERY_TICKS;
            case HIDING -> c.reactionHideTicks.get();
            case RECOVERING -> c.reactionRecoveryTicks.get();
            // Captivity has no timer of its own; the custody record owns its lifetime.
            case CAPTIVE, CALM -> 0L;
        };
    }

    @Nullable
    private static ServerPlayer offenderOf(ServerLevel level, ActiveCrimeReactionController controller) {
        UUID offender = controller.offenderId();
        if (offender == null || level.getServer() == null) {
            return null;
        }
        ServerPlayer player = level.getServer().getPlayerList().getPlayer(offender);
        return player != null && player.level() == level ? player : null;
    }

    @Nullable
    private static ServerLevel levelOf(MinecraftServer server, ResourceLocation dimension) {
        return server.getLevel(ResourceKey.create(Registries.DIMENSION, dimension));
    }

    /** The nearest law responder that is not the offender, within the configured report radius. */
    @Nullable
    private static LivingEntity nearestResponder(ServerLevel level, LivingEntity villager, @Nullable UUID offender) {
        double radius = McaCrimeConfig.COMMON.reportRadius.get();
        AABB box = villager.getBoundingBox().inflate(radius);
        LivingEntity best = null;
        double bestDistance = Double.MAX_VALUE;
        for (LivingEntity candidate : level.getEntitiesOfClass(LivingEntity.class, box,
                entity -> entity != villager && EntitySelectors.isAvailableResponder(entity))) {
            if (offender != null && offender.equals(candidate.getUUID())) {
                continue;
            }
            double distance = villager.distanceToSqr(candidate);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = candidate;
            }
        }
        return best;
    }

    /** The nearest adult MCA villager other than this one, as a refuge candidate. */
    @Nullable
    private static LivingEntity nearestAlly(ServerLevel level, LivingEntity villager) {
        AABB box = villager.getBoundingBox().inflate(16.0);
        LivingEntity best = null;
        double bestDistance = Double.MAX_VALUE;
        for (LivingEntity candidate : level.getEntitiesOfClass(LivingEntity.class, box,
                entity -> entity != villager && NpcAwareness.isAwake(entity) && McaCompat.isMcaVillager(entity)
                        && McaCompat.isAdult(entity))) {
            double distance = villager.distanceToSqr(candidate);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = candidate;
            }
        }
        return best;
    }

    private static ReactionFactors factorsFor(ServerLevel level, LivingEntity villager, ServerPlayer offender) {
        if (level.getServer() == null) {
            return ReactionFactors.unknown();
        }
        VillagerCrimeProfile profile = CrimeWorldData.get(level.getServer())
                .villagerProfile(villager.getUUID()).orElse(null);
        OffenderMemory memory = profile == null ? null : profile.offenderMemories().get(offender.getUUID());
        return ReactionFactors.of(villager.getUUID(), McaCompat.getHearts(offender, villager),
                McaCompat.isGuard(villager), McaCompat.isAdult(villager),
                memory == null ? 0 : memory.attempts(), false);
    }

    /** Marks a villager as taken, so the controller suppresses movement rather than fleeing forever. */
    public static void markCaptive(ServerLevel level, LivingEntity villager, @Nullable UUID captor) {
        trigger(level, villager, captor, VictimReactionState.CAPTIVE, null);
    }

    /** Ends a captivity reaction, leaving the villager shaken but free. */
    public static void endCaptive(ServerLevel level, UUID villager) {
        ActiveCrimeReactionController controller = ACTIVE.get(villager);
        if (controller != null && controller.state() == VictimReactionState.CAPTIVE) {
            transition(level, controller, VictimReactionState.RECOVERING,
                    durationOf(VictimReactionState.RECOVERING));
        }
    }

    /** The observation this villager is currently carrying, for dialogue and debugging. */
    @Nullable
    public static CrimeObservation carriedObservation(MinecraftServer server, UUID villager) {
        ActiveCrimeReactionController controller = ACTIVE.get(villager);
        if (controller == null || controller.observationId() == null || server == null) {
            return null;
        }
        return CrimeWorldData.get(server).observation(controller.observationId()).orElse(null);
    }

    /** Exposed for the debug command: a snapshot of who is reacting and how. */
    public static List<ActiveCrimeReactionController> snapshot() {
        return List.copyOf(ACTIVE.values());
    }

    private static boolean decideDynamic(ServerLevel level, ActiveCrimeReactionController controller,
                                          LivingEntity villager, ServerPlayer offender, long now, boolean coercive) {
        var context = ThreatContexts.build(villager, offender, coercive);
        var evaluated = ThreatEvaluator.evaluate(context, ThreatContexts.options());
        int interval = McaCrimeConfig.COMMON.threatReevaluationTicks.get();
        controller.evaluatedThreat(now, interval, evaluated);
        var next = ThreatEvaluator.stabilize(controller.state(), evaluated.response(), controller.ticksInState(now), interval);
        // A stalling victim eventually surrenders if the threat stays focused and no help arrives.
        if (next == VictimReactionState.STALLING && (controller.finishedStalling()
                || controller.state() == next && controller.ticksInState(now) >= 60)) {
            controller.finishStalling();
            next = VictimReactionState.COMPLYING;
        }
        if (next == VictimReactionState.COMPLYING || next == VictimReactionState.STALLING) {
            controller.markCoerced(ActionSessionManager.activeCoerciveAgainst(villager.getUUID()).map(ActionSession::sessionId).orElse(null));
        }
        transition(level, controller, next, durationOf(next));
        if (next == VictimReactionState.COMPLYING || next == VictimReactionState.STALLING) holdComplying(villager, offender);
        return true;
    }

    private static boolean tickDeliberating(ServerLevel level, ActiveCrimeReactionController controller,
                                             LivingEntity villager, ServerPlayer offender, long now) {
        boolean coerced = ActionSessionManager.activeCoerciveAgainst(villager.getUUID()).isPresent();
        if (offender == null || !coerced || !ThreatContexts.aimedAt(offender, villager))
            return transition(level, controller, VictimReactionState.FLEEING, durationOf(VictimReactionState.FLEEING));
        if (controller.shouldEvaluateThreat(now)) decideDynamic(level, controller, villager, offender, now, coerced);
        if (controller.state() == VictimReactionState.STALLING) holdComplying(villager, offender);
        else McaCompat.faceEntity(villager, offender);
        return true;
    }
}
