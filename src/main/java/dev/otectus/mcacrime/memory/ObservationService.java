package dev.otectus.mcacrime.memory;

import dev.otectus.mcacrime.McaCrimeConfig;
import dev.otectus.mcacrime.ai.CrimeReactionService;
import dev.otectus.mcacrime.api.event.CrimeObservationEvent;
import dev.otectus.mcacrime.compat.McaCompat;
import dev.otectus.mcacrime.detect.EntitySelectors;
import dev.otectus.mcacrime.detect.WitnessResult;
import dev.otectus.mcacrime.state.world.CrimeWorldData;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.common.MinecraftForge;

import javax.annotation.Nullable;
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
     * <p>{@code offender} is a {@code LivingEntity} rather than a player because 0.5.1 has villager
     * offenders too: a thief's mugging is observed, reported and reacted to by exactly the same
     * machinery. Nothing here ever needed the player half of the type.
     *
     * @param incidentId the crime record this belongs to; observations are addressable by it
     * @param witnesses  the already-computed line-of-sight result, reused rather than rescanned
     * @return the stored observations, newest first, or empty when observations are disabled
     */
    public static List<CrimeObservation> record(ServerLevel level, LivingEntity offender,
                                                @Nullable LivingEntity victim, ResourceLocation crimeId,
                                                UUID incidentId, WitnessResult witnesses) {
        return record(level, offender, victim, crimeId, incidentId, witnesses,
                dev.otectus.mcacrime.crime.type.CrimeTypeRegistry.getOrBuiltin(crimeId)
                        .map(dev.otectus.mcacrime.crime.type.CrimeType::awareness)
                        .orElse(dev.otectus.mcacrime.crime.type.CrimeAwareness.defaults(crimeId)));
    }

    public static List<CrimeObservation> record(ServerLevel level, LivingEntity offender,
                                                @Nullable LivingEntity victim, ResourceLocation crimeId,
                                                UUID incidentId, WitnessResult witnesses,
                                                dev.otectus.mcacrime.crime.type.CrimeAwareness awareness) {
        return record(level, offender, victim, crimeId, incidentId, witnesses, awareness,
                level == null ? 0L : level.getGameTime());
    }

    /**
     * The same scan dated to when the act was actually observed (0.7.2 §14.3).
     *
     * <p>Every caller but one commits in the tick the act happened, and for them {@code observedAt}
     * is simply {@code level.getGameTime()}. A thrown Sand Bottle is the exception: its observations
     * belong to the moment it left the thrower's hand, not to the moment it landed up to three
     * seconds later, and dating them at impact would quietly extend the statute on every one of them
     * and misreport when the throw was seen.
     */
    public static List<CrimeObservation> record(ServerLevel level, LivingEntity offender,
                                                @Nullable LivingEntity victim, ResourceLocation crimeId,
                                                UUID incidentId, WitnessResult witnesses,
                                                dev.otectus.mcacrime.crime.type.CrimeAwareness awareness,
                                                long observedAt) {
        MinecraftServer server = level == null ? null : level.getServer();
        if (!dev.otectus.mcacrime.state.world.ServerMutationGate.allows(server) || !McaCrimeConfig.COMMON.enableObservations.get()) {
            return List.of();
        }
        long now = observedAt;
        long expiresAt = now + McaCrimeConfig.COMMON.observationStatuteTicks.get();
        BlockPos where = victim != null ? victim.blockPosition() : offender.blockPosition();
        UUID victimId = victim == null ? null : victim.getUUID();
        List<CrimeObservation> stored = new ArrayList<>();
        Set<UUID> covered = new HashSet<>();

        // 1. The direct victim. They do not need line of sight to know it happened to them, and their
        //    identity confidence is total when the offender was standing in front of them.
        if (victim != null && dev.otectus.mcacrime.ai.NpcAwareness.canObserveAct(victim) && McaCompat.isMcaVillager(victim)) {
            boolean faceToFace = !offender.isInvisible() && victim.hasLineOfSight(offender)
                    && !dev.otectus.mcacrime.effect.SandBlindness.blocksSight(victim, offender)
                    && !victim.hasEffect(net.minecraft.world.effect.MobEffects.BLINDNESS)
                    && !McaCompat.isVillagerSleeping(victim);
            covered.add(victim.getUUID());
            CrimeObservation observation = build(incidentId, victim, ObserverRole.DIRECT_VICTIM,
                    faceToFace ? offender.getUUID() : null, victimId, crimeId, level, where, now, expiresAt,
                    faceToFace ? 1.0F : 0.0F, faceToFace, true, awareness.soundRadius() > 0);
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
            if (!(entity instanceof LivingEntity witness) || !dev.otectus.mcacrime.ai.NpcAwareness.canObserveAct(witness)) {
                continue;
            }
            ObserverRole role = EntitySelectors.isResponder(witness) ? ObserverRole.GUARD : ObserverRole.EYEWITNESS;
            var perceived = dev.otectus.mcacrime.detect.WitnessChecker.perceive(witness, offender,
                    victim == null ? offender : victim, awareness);
            if (!perceived.aware()) continue;
            CrimeObservation observation = build(incidentId, witness, role,
                    perceived.identifiesActor() ? offender.getUUID() : null, victimId,
                    crimeId, level, where, now, expiresAt, perceived.confidence(),
                    perceived.identifiesActor(), perceived.sawAct(), perceived.heardAct());
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

        // 3. The offender's family who saw it and chose not to report it. They were filtered out of
        //    the witness set at selection, so nothing above this point knows they were there — and
        //    that is deliberate: Heat, community standing and family heart loss must all behave as
        //    though the crime was unseen. What they do keep is the memory of it, stored WITHHELD so
        //    it can never be filed or relayed but can still be spoken about.
        for (UUID loyalId : witnesses.loyalIds()) {
            if (!covered.add(loyalId)) {
                continue;
            }
            Entity entity = level.getEntity(loyalId);
            if (!(entity instanceof LivingEntity relative) || !dev.otectus.mcacrime.ai.NpcAwareness.canObserveAct(relative)) {
                continue;
            }
            var perceived = dev.otectus.mcacrime.detect.WitnessChecker.perceive(relative, offender,
                    victim == null ? offender : victim, awareness);
            if (!perceived.aware()) continue;
            CrimeObservation observation = new CrimeObservation(UUID.randomUUID(), incidentId, loyalId,
                    ObserverRole.EYEWITNESS, perceived.identifiesActor() ? offender.getUUID() : null, victimId,
                    crimeId, level.dimension().location(), where, now, perceived.confidence(),
                    perceived.identifiesActor(), perceived.sawAct(), perceived.heardAct(),
                    ReportState.WITHHELD, expiresAt, false);
            if (store(server, observation)) {
                stored.add(observation);
            }
        }

        // 4. Everybody who heard it. One extra bounded scan, over a larger radius than sight, because
        //    a wall stops a line of sight and does not stop a scream.
        double hearingRadius = awareness.soundRadius() * McaCrimeConfig.COMMON.auditoryWitnessRadiusMultiplier.get();
        if (hearingRadius > 0) {
            AABB box = new AABB(where).inflate(hearingRadius);
            for (LivingEntity listener : level.getEntitiesOfClass(LivingEntity.class, box,
                    entity -> entity != offender && dev.otectus.mcacrime.ai.NpcAwareness.canObserveAct(entity) && !entity.isSpectator()
                            && McaCompat.isMcaVillager(entity))) {
                if (!covered.add(listener.getUUID())) {
                    continue;
                }
                var perceived = dev.otectus.mcacrime.detect.WitnessChecker.perceive(listener, offender,
                        victim == null ? offender : victim, awareness);
                if (!perceived.heardAct() || stored.size() >= McaCrimeConfig.COMMON.maxStoredWitnesses.get() + 1) continue;
                CrimeObservation observation = build(incidentId, listener, ObserverRole.HEARING_WITNESS,
                        null, victimId, crimeId, level, where, now, expiresAt,
                        0, false, false, true);
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
     * saw the act while awake but is a child or is currently tied up in the offender's basement,
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
        if (!dev.otectus.mcacrime.ai.NpcAwareness.canSpeakOrReport(observer) || !McaCompat.isAdult(observer)) {
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
        if (MinecraftForge.EVENT_BUS.post(pre)) {
            return false;
        }
        if (!CrimeWorldData.get(server).addObservation(observation)) {
            return false;
        }
        WitnessSocialService.schedule(observation.observerId(), observation.observedAt());
        dev.otectus.mcacrime.incident.IncidentNotifications.post(new CrimeObservationEvent.Post(observation.observationId(),
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
    private static void startReaction(ServerLevel level, LivingEntity observer, LivingEntity offender,
                                      CrimeObservation observation) {
        if (!observation.pending()) {
            return;
        }
        if (dev.otectus.mcacrime.detect.EntitySelectors.isResponder(observer)) {
            ReportService.fileDirect(level, observer, observation);
            return;
        }
        CrimeReactionService.trigger(level, observer, observation.suspectedActorId(),
                dev.otectus.mcacrime.mask.MaskReactionPolicy.initialReaction(observation.identifiesActor()),
                observation.observationId());
    }

    /**
     * Starts the same reactions {@link #record} would, for a crime whose attribution is hidden by a mask
     * (§12.2, 0.7.0). Nothing is stored: no observation, no victim memory, no report. A mask removes the
     * record, never the reaction — the victim is still looking at somebody with a weapon, so they still
     * enter {@code THREATENED} and are still judged by the same evaluator as on the unmasked path.
     *
     * <p>The gates are {@link #record}'s own, deliberately copied rather than generalised: an observer
     * who could not react to an unmasked crime must not start reacting because a mask was worn.
     * Responders are skipped in both halves because a masked crime's responder consequence is the
     * masked pursuit installed by {@code incident/IncidentService.maskedConsequences}, not a filing
     * against a name nobody saw.
     *
     * <p>{@code enableObservations} is not consulted — nothing here is stored, and
     * {@link CrimeReactionService#trigger} already honours {@code enableVillagerReactions}.
     *
     * <p>{@link #record} steps 3 (loyal family) and 4 (hearing-only listeners) have no counterpart here:
     * neither of them starts a reaction on the unmasked path either, the first because a withheld
     * memory is all a loyal relative keeps and the second because hearing alone is stored and left.
     *
     * <p>The offender's real UUID is what reaches the controller. That is safe and necessary: the
     * controller's offender id lives in memory only and is never written to world data, memory or
     * observations, and {@code tickThreatened} drops straight to RECOVERING when it cannot resolve an
     * offender, which would be the same dead channel the mask bug produced.
     */
    public static void reactUnattributed(ServerLevel level, LivingEntity offender,
                                         @Nullable LivingEntity victim, WitnessResult witnesses,
                                         dev.otectus.mcacrime.crime.type.CrimeAwareness awareness) {
        MinecraftServer server = level == null ? null : level.getServer();
        if (!dev.otectus.mcacrime.state.world.ServerMutationGate.allows(server)) {
            return;
        }
        Set<UUID> covered = new HashSet<>();

        // 1. The direct victim, on record's own gate: awake, an MCA villager, not a responder, and in a
        //    state to have reacted at all.
        if (victim != null && dev.otectus.mcacrime.ai.NpcAwareness.canObserveAct(victim) && McaCompat.isMcaVillager(victim)) {
            boolean faceToFace = !offender.isInvisible() && victim.hasLineOfSight(offender)
                    && !dev.otectus.mcacrime.effect.SandBlindness.blocksSight(victim, offender)
                    && !victim.hasEffect(net.minecraft.world.effect.MobEffects.BLINDNESS)
                    && !McaCompat.isVillagerSleeping(victim);
            covered.add(victim.getUUID());
            if (!EntitySelectors.isResponder(victim) && canReport(level, victim)) {
                CrimeReactionService.trigger(level, victim, offender.getUUID(),
                        dev.otectus.mcacrime.mask.MaskReactionPolicy.initialReaction(faceToFace), null);
            }
        }

        // 2. The eyewitnesses the line-of-sight scan already found, perceived exactly as record perceives
        //    them so that a witness who cannot make out the act still does nothing about it.
        for (UUID witnessId : witnesses.witnessIds()) {
            if (!covered.add(witnessId)) {
                continue;
            }
            Entity entity = level.getEntity(witnessId);
            if (!(entity instanceof LivingEntity witness) || !dev.otectus.mcacrime.ai.NpcAwareness.canObserveAct(witness)) {
                continue;
            }
            if (EntitySelectors.isResponder(witness) || !canReport(level, witness)) {
                continue;
            }
            var perceived = dev.otectus.mcacrime.detect.WitnessChecker.perceive(witness, offender,
                    victim == null ? offender : victim, awareness);
            if (!perceived.aware()) continue;
            CrimeReactionService.trigger(level, witness, offender.getUUID(),
                    dev.otectus.mcacrime.mask.MaskReactionPolicy.initialReaction(perceived.identifiesActor()), null);
        }
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
