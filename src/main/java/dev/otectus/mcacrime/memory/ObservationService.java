package dev.otectus.mcacrime.memory;

import dev.otectus.mcacrime.McaCrimeConfig;
import dev.otectus.mcacrime.ai.CrimeReactionService;
import dev.otectus.mcacrime.ai.VictimReactionState;
import dev.otectus.mcacrime.api.event.CrimeObservationEvent;
import dev.otectus.mcacrime.compat.McaCompat;
import dev.otectus.mcacrime.detect.EntitySelectors;
import dev.otectus.mcacrime.detect.WitnessResult;
import dev.otectus.mcacrime.state.world.CrimeWorldData;
import net.minecraft.core.BlockPos;
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
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Turns "a crime just happened here" into the set of NPCs who know about it, and starts them reacting
 * (spec §12.1/§12.2).
 *
 * <p>This is the service that gives {@code countWitnesses} something to be replaced by. The witness
 * scan still runs first and still decides the ledger's witness set — that path is unchanged and its
 * privacy rules still hold — and this service then <em>enriches</em> the result: it says what role each
 * of those witnesses was in, adds the people who heard rather than saw, records who could not report
 * at all, and hands each of them to the reaction controller.
 *
 * <p>Two scans happen per crime and no more: the caller's line-of-sight scan, whose identities are
 * reused here rather than recomputed, and one bounded hearing scan over a slightly larger radius.
 * Crimes are event-driven, so this cost is paid once per crime and never per tick.
 *
 * <p>Heat is deliberately not touched here. §12.2 is explicit that an observation existing is not a
 * reason to add Heat — the crime's own commit already applied whatever the crime type is worth, and
 * charging again on top of it would make a crowded street cost more Heat than a quiet one for reasons
 * the player cannot see.
 */
public final class ObservationService {

    /** Distance at which an eyewitness is treated as having seen it perfectly. */
    private static final double CLOSE_RANGE = 4.0;
    /** Confidence multiplier applied when the sound had to travel through something solid. */
    private static final float OBSTRUCTED_HEARING = 0.5F;

    private ObservationService() {
    }

    /**
     * Records what everybody nearby knows about one incident and starts the reactions that follow.
     *
     * @param incidentId the crime record this belongs to; observations are addressable by it
     * @param witnesses  the already-computed line-of-sight result, reused rather than rescanned
     * @return the stored observations, newest first, or empty when observations are disabled
     */
    public static List<CrimeObservation> record(ServerLevel level, ServerPlayer offender,
                                                @Nullable LivingEntity victim, ResourceLocation crimeId,
                                                UUID incidentId, WitnessResult witnesses) {
        MinecraftServer server = level == null ? null : level.getServer();
        if (server == null || !McaCrimeConfig.COMMON.enableObservations.get()) {
            return List.of();
        }
        long now = level.getGameTime();
        long expiresAt = now + McaCrimeConfig.COMMON.observationStatuteTicks.get();
        BlockPos where = victim != null ? victim.blockPosition() : offender.blockPosition();
        UUID victimId = victim == null ? null : victim.getUUID();
        List<CrimeObservation> stored = new ArrayList<>();
        Set<UUID> covered = new HashSet<>();

        // 1. The direct victim. They do not need line of sight to know it happened to them, and their
        //    identity confidence is total when the offender was standing in front of them.
        if (victim != null && victim.isAlive() && McaCompat.isMcaVillager(victim)) {
            boolean faceToFace = victim.distanceToSqr(offender) <= CLOSE_RANGE * CLOSE_RANGE
                    && victim.hasLineOfSight(offender);
            covered.add(victim.getUUID());
            CrimeObservation observation = build(incidentId, victim, ObserverRole.DIRECT_VICTIM,
                    offender.getUUID(), victimId, crimeId, level, where, now, expiresAt,
                    faceToFace ? 1.0F : 0.7F, faceToFace, true, true);
            if (store(server, observation)) {
                stored.add(observation);
                startReaction(level, victim, offender, observation);
            }
        }

        // 2. Everybody the line-of-sight scan already identified. Reusing those identities is what
        //    keeps this to one extra scan, and it also guarantees the observation set can never name
        //    somebody the ledger's witness set does not.
        for (UUID witnessId : witnesses.witnessIds()) {
            if (!covered.add(witnessId)) {
                continue;
            }
            Entity entity = level.getEntity(witnessId);
            if (!(entity instanceof LivingEntity witness) || !witness.isAlive()) {
                continue;
            }
            ObserverRole role = EntitySelectors.isResponder(witness) ? ObserverRole.GUARD : ObserverRole.EYEWITNESS;
            float confidence = role.baseConfidence() * distanceFalloff(witness, offender);
            CrimeObservation observation = build(incidentId, witness, role, offender.getUUID(), victimId,
                    crimeId, level, where, now, expiresAt, confidence, true, true, true);
            if (store(server, observation)) {
                stored.add(observation);
                if (role == ObserverRole.GUARD) {
                    // A responder who saw it has nobody to walk to: it files on the spot (§12.3 step 1).
                    ReportService.fileDirect(level, witness, observation);
                } else {
                    startReaction(level, witness, offender, observation);
                }
            }
        }

        // 3. Everybody who heard it. One extra bounded scan, over a larger radius than sight, because
        //    a wall stops a line of sight and does not stop a scream.
        int hearingRadius = McaCrimeConfig.COMMON.hearingWitnessRadius.get();
        if (hearingRadius > 0) {
            AABB box = new AABB(where).inflate(hearingRadius);
            for (LivingEntity listener : level.getEntitiesOfClass(LivingEntity.class, box,
                    entity -> entity != offender && entity.isAlive() && !entity.isSpectator()
                            && McaCompat.isMcaVillager(entity))) {
                if (!covered.add(listener.getUUID())) {
                    continue;
                }
                float confidence = ObserverRole.HEARING_WITNESS.baseConfidence()
                        * distanceFalloff(listener, offender)
                        * (listener.hasLineOfSight(offender) ? 1.0F : OBSTRUCTED_HEARING);
                CrimeObservation observation = build(incidentId, listener, ObserverRole.HEARING_WITNESS,
                        offender.getUUID(), victimId, crimeId, level, where, now, expiresAt,
                        confidence, false, false, true);
                if (store(server, observation)) {
                    stored.add(observation);
                    startReaction(level, listener, offender, observation);
                }
            }
        }
        return List.copyOf(stored);
    }

    /**
     * Builds one observation, applying the incapacity rules from §12.2 as it goes.
     *
     * <p>Incapacity lowers <em>reporting ability</em> rather than deleting knowledge. A villager who
     * was asleep when it happened, or is a child, or is currently tied up in the offender's basement,
     * still knows what they know — they simply cannot walk to a guard about it, which is why the
     * observation is stored as {@link ReportState#SUPPRESSED} instead of being discarded. Discarding
     * it would mean the villager could never speak about it afterwards either.
     */
    private static CrimeObservation build(UUID incidentId, LivingEntity observer, ObserverRole role,
                                          UUID suspect, @Nullable UUID victimId, ResourceLocation crimeId,
                                          ServerLevel level, BlockPos where, long now, long expiresAt,
                                          float confidence, boolean sawActor, boolean sawAct, boolean heardAct) {
        boolean incapable = !canReport(level, observer);
        boolean asleep = McaCompat.isVillagerSleeping(observer);
        float finalConfidence = asleep ? confidence * 0.3F : confidence;
        return new CrimeObservation(UUID.randomUUID(), incidentId, observer.getUUID(), role, suspect,
                victimId, crimeId, level.dimension().location(), where, now, finalConfidence,
                sawActor && !asleep, sawAct && !asleep, heardAct,
                incapable ? ReportState.SUPPRESSED : ReportState.PENDING, expiresAt);
    }

    /**
     * Whether this observer is in any state to carry a report to somebody. Children and held captives
     * are the two cases that matter in practice: a kidnapper's whole plan stops working if the person
     * they are holding can file a report from inside the cell.
     */
    private static boolean canReport(ServerLevel level, LivingEntity observer) {
        if (!McaCompat.isAdult(observer)) {
            return false;
        }
        MinecraftServer server = level.getServer();
        return server == null || !CrimeWorldData.get(server).isCaptive(observer.getUUID());
    }

    /** Fires the cancellable pre-event, stores, and fires the post-event. */
    private static boolean store(MinecraftServer server, CrimeObservation observation) {
        CrimeObservationEvent.Pre pre = new CrimeObservationEvent.Pre(observation.observationId(),
                observation.incidentId(), observation.observerId(), observation.suspectedActorId(),
                observation.victimId(), observation.actionId(), observation.role(),
                observation.location(), observation.confidence());
        if (NeoForge.EVENT_BUS.post(pre).isCanceled()) {
            return false;
        }
        if (!CrimeWorldData.get(server).addObservation(observation)) {
            return false;
        }
        NeoForge.EVENT_BUS.post(new CrimeObservationEvent.Post(observation.observationId(),
                observation.incidentId(), observation.observerId(), observation.suspectedActorId(),
                observation.victimId(), observation.actionId(), observation.role(),
                observation.location(), observation.confidence()));
        return true;
    }

    /**
     * Starts the villager reacting. Everybody enters {@code THREATENED} and decides for themselves what
     * to do about it — running, resisting, or fetching a guard is the state machine's call, made from
     * personality and history, not this service's.
     */
    private static void startReaction(ServerLevel level, LivingEntity observer, ServerPlayer offender,
                                      CrimeObservation observation) {
        if (!observation.pending()) {
            return;
        }
        CrimeReactionService.trigger(level, observer, offender.getUUID(),
                VictimReactionState.THREATENED, observation.observationId());
    }

    /**
     * Linear falloff from {@link #CLOSE_RANGE} to the sight radius, floored at a quarter. Somebody at
     * the far edge of the radius is genuinely less sure who they saw, but they are not useless: a
     * quarter-confidence sighting is exactly what should produce an investigation rather than an arrest.
     */
    private static float distanceFalloff(LivingEntity observer, LivingEntity actor) {
        double distance = Math.sqrt(observer.distanceToSqr(actor));
        double radius = Math.max(CLOSE_RANGE + 1.0, McaCrimeConfig.COMMON.witnessRadius.get());
        if (distance <= CLOSE_RANGE) {
            return 1.0F;
        }
        double scaled = 1.0 - (distance - CLOSE_RANGE) / (radius - CLOSE_RANGE);
        return (float) Math.max(0.25, Math.min(1.0, scaled));
    }
}
