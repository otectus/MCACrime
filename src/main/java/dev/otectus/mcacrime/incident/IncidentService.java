package dev.otectus.mcacrime.incident;

import dev.otectus.mcacrime.McaCrimeConfig;
import dev.otectus.mcacrime.api.event.CrimeCommittedEvent;
import dev.otectus.mcacrime.api.event.CrimeWitnessedEvent;
import dev.otectus.mcacrime.api.event.NpcCrimeCommittedEvent;
import dev.otectus.mcacrime.api.model.CrimeCommunityKey;
import dev.otectus.mcacrime.api.model.CrimeRecordView;
import dev.otectus.mcacrime.compat.McaCompat;
import dev.otectus.mcacrime.crime.type.CrimeAwareness;
import dev.otectus.mcacrime.crime.type.CrimeIds;
import dev.otectus.mcacrime.crime.type.CrimeTypeRegistry;
import dev.otectus.mcacrime.detect.CrimeCommunityResolver;
import dev.otectus.mcacrime.detect.CrimeDetector;
import dev.otectus.mcacrime.detect.WitnessChecker;
import dev.otectus.mcacrime.detect.WitnessResult;
import dev.otectus.mcacrime.engine.CrimeState;
import dev.otectus.mcacrime.integration.CrimeIntegrationHooks;
import dev.otectus.mcacrime.ledger.CrimeContext;
import dev.otectus.mcacrime.ledger.CrimeFlag;
import dev.otectus.mcacrime.ledger.CrimeRecord;
import dev.otectus.mcacrime.ledger.Resolution;
import dev.otectus.mcacrime.memory.ObservationService;
import dev.otectus.mcacrime.memory.VictimMemoryService;
import dev.otectus.mcacrime.state.world.CrimeWorldData;
import dev.otectus.mcacrime.state.world.ServerMutationGate;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;

import org.jetbrains.annotations.Nullable;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;

/** The checked, ordered commit tail for player, NPC and ransom incidents. */
public final class IncidentService {
    private IncidentService() {}

    public static Optional<CrimeRecordView> commitDirect(ServerPlayer offender, ResourceLocation crimeId,
            @Nullable LivingEntity victim, ServerLevel level, WitnessResult witnesses, String detection) {
        return commitPlayer(UUID.randomUUID(), offender, crimeId, victim, level, witnesses, detection, Map.of());
    }

    /** Stable IDs let a completed hit/action be retried without repeating its consequences. */
    public static Optional<CrimeRecordView> commitPlayer(UUID incidentId, ServerPlayer offender,
            ResourceLocation crimeId, @Nullable LivingEntity victim, ServerLevel level,
            WitnessResult witnesses, String detection, Map<String, String> provenance) {
        return commitPlayer(incidentId, offender, crimeId, victim, level, witnesses, detection, provenance,
                null, null);
    }

    /**
     * The same commit with Karma and Heat the caller has decided rather than the crime type has.
     *
     * <p>Contraband is why this exists: what carrying a banned item is worth is an operator's decision
     * (0.7.0, {@code contraband.contrabandKarma} / {@code contrabandHeat}), and the alternative was
     * either a second copy of this whole tail or a crime type mutated underneath the registry. A null
     * override means "use the crime type", which is what every older caller passes.
     */
    public static Optional<CrimeRecordView> commitPlayer(UUID incidentId, ServerPlayer offender,
            ResourceLocation crimeId, @Nullable LivingEntity victim, ServerLevel level,
            WitnessResult witnesses, String detection, Map<String, String> provenance,
            @Nullable Long karmaOverride, @Nullable Long heatOverride) {
        return commitPlayer(incidentId, offender, crimeId, victim, level, witnesses, detection, provenance,
                karmaOverride, heatOverride, null);
    }

    /**
     * The same commit against knowledge captured when the act began rather than when it lands (§14.3).
     *
     * <p>Only a delayed act needs this. A punch is witnessed and committed in one tick, so the fresh
     * scan below is the truth; a thrown bottle is not, and re-deriving its witnesses and its thrower's
     * mask at impact would let three seconds of flight rewrite what people saw. When a snapshot is
     * supplied the witness scan and the mask decision are taken from it verbatim, and the rest of the
     * tail — Karma, deferred Heat, evidence, notifications — is untouched, because the one thing that
     * must not fork is the commit order itself.
     */
    public static Optional<CrimeRecordView> commitPlayer(UUID incidentId, ServerPlayer offender,
            ResourceLocation crimeId, @Nullable LivingEntity victim, ServerLevel level,
            WitnessResult witnesses, String detection, Map<String, String> provenance,
            @Nullable Long karmaOverride, @Nullable Long heatOverride,
            @Nullable ObservationSnapshot snapshot) {
        return commitPlayer(incidentId, offender, crimeId, victim, level, witnesses, detection, provenance,
                karmaOverride, heatOverride, snapshot, null);
    }

    /**
     * The same commit against a typed {@link IncidentContext} rather than the entity-derived
     * jurisdiction rule (0.7.4, spec §10.3).
     *
     * <p>One caller today, and it is the reason the type exists: a theft from a settlement's own
     * container belongs to that settlement, not to the offender's home village and not to whichever
     * villager the policy happens to name as owner. Every older caller passes nothing and keeps the
     * original rule exactly -- victim home for a player's crime, offender home for an NPC's -- because
     * for every other crime in this mod that rule is right.
     *
     * <p>The context also writes the place and the reason into the record's context map, so an operator
     * reading a charge can see which of the three candidate communities won and why.
     */
    public static Optional<CrimeRecordView> commitPlayer(UUID incidentId, ServerPlayer offender,
            ResourceLocation crimeId, @Nullable LivingEntity victim, ServerLevel level,
            WitnessResult witnesses, String detection, Map<String, String> provenance,
            @Nullable Long karmaOverride, @Nullable Long heatOverride,
            @Nullable ObservationSnapshot snapshot, @Nullable IncidentContext incidentContext) {
        if (!available(level) || offender == null || offender.getServer() != level.getServer()) return Optional.empty();
        var type = CrimeTypeRegistry.getOrBuiltin(crimeId).orElse(null);
        if (type == null) return Optional.empty();
        CrimeAwareness awareness = "mug_attempt".equals(detection) ? CrimeAwareness.robbery() : type.awareness();
        WitnessResult effective = snapshot != null ? snapshot.witnesses()
                : "command".equals(detection) || "jailbreak".equals(detection)
                ? witnesses == null ? WitnessResult.none() : witnesses
                : WitnessChecker.resolve(level, offender, victim, awareness);
        var c = McaCrimeConfig.COMMON;
        long karma = karmaOverride != null ? karmaOverride
                : CrimeDetector.karmaFor(type, effective.witnessed(), c.unwitnessedKarmaFactor.get());
        long heat = heatOverride != null ? heatOverride
                : CrimeDetector.heatFor(type, effective.witnessed(), c.requireWitnessForHeat.get());
        // A mask is decided once, here, and the decision is what the rest of the tail reads. The record
        // keeps the full Heat; only the number handed to applyIncident becomes 0, so nothing downstream
        // has to know the difference between a crime that was cheap and a crime that was hidden.
        boolean masked = c.maskEnabled.get() && (snapshot != null ? snapshot.masked()
                : dev.otectus.mcacrime.mask.Masks.isMasked(offender));
        boolean deferred = masked && c.maskSuppressesHeat.get()
                && dev.otectus.mcacrime.mask.Masks.defersHeatFor(detection) && heat != 0L;
        Map<String, String> context = context(victim, effective, detection);
        if (masked) context.put(CrimeContext.MASKED, "true");
        if (deferred) context.put(CrimeContext.HEAT_DEFERRED, Long.toString(heat));
        // The typed context first, the caller's own strings last: a caller that wants to override one
        // of these keys may, and nothing silently overwrites what a caller deliberately passed.
        if (incidentContext != null) context.putAll(incidentContext.provenance());
        context.putAll(provenance);
        CrimeRecord record = record(incidentId, offender, victim, crimeId, level, effective,
                heat, karma, 0, context, incidentContext);
        boolean hideIdentity = dev.otectus.mcacrime.mask.MaskReactionPolicy.hidesAttribution(masked,
                c.maskHidesIdentityFromWitnesses.get());
        return commitPrepared(CrimeWorldData.get(level.getServer()), record,
                () -> {
                    CrimeState.applyIncident(offender, karma, deferred ? 0L : heat, crimeId,
                            incidentId.toString());
                    if (deferred) maskedConsequences(offender, level, effective, crimeId, incidentId, heat);
                },
                () -> {
                    // A witness who never saw a face has nothing to attribute. Skipping the whole
                    // evidence pair rather than filtering it downstream is what keeps observation and
                    // victim memory agreeing about who, if anybody, the offender was. What the mask
                    // hides is only that attribution: the victim and the bystanders still react in real
                    // time, to the person they can plainly see standing there.
                    if (!hideIdentity) {
                        evidence(level, offender, victim, record, effective, awareness,
                                "mug_attempt".equals(detection),
                                snapshot == null ? level.getGameTime() : snapshot.observedAt());
                    } else {
                        ObservationService.reactUnattributed(level, offender, victim, effective, awareness);
                    }
                },
                () -> {
                    IncidentNotifications.safely(() -> CrimeIntegrationHooks.onCommitted(level.getServer(), record.view()));
                    if (effective.witnessed()) IncidentNotifications.post(new CrimeWitnessedEvent(offender,
                            crimeId, record.victim(), effective.totalWitnesses(), effective.witnessIds()));
                    dev.otectus.mcacrime.event.AmbientMessages.loyalWitness(offender, effective);
                    IncidentNotifications.post(new CrimeCommittedEvent(offender, crimeId, record.victim(),
                            effective.witnessed(), karma, heat, incidentId, record.view()));
                });
    }

    public static Optional<CrimeRecordView> commitNpc(LivingEntity offender, ResourceLocation crimeId,
            @Nullable LivingEntity victim, ServerLevel level, WitnessResult witnesses, String detection,
            Set<CrimeFlag> flags) {
        return commitNpc(UUID.randomUUID(), offender, crimeId, victim, level, detection, flags);
    }

    public static Optional<CrimeRecordView> commitNpc(UUID incidentId, LivingEntity offender,
            ResourceLocation crimeId, @Nullable LivingEntity victim, ServerLevel level, String detection,
            Set<CrimeFlag> flags) {
        if (!available(level) || offender == null || offender.level() != level) return Optional.empty();
        var type = CrimeTypeRegistry.getOrBuiltin(crimeId).orElse(null);
        if (type == null) return Optional.empty();
        var effective = WitnessChecker.resolve(level, offender, victim, type.awareness());
        var context = context(victim, effective, detection);
        context.put(CrimeContext.OFFENDER_KIND, "npc");
        context.put(CrimeContext.FLAGS, CrimeFlag.encode(flags == null || flags.isEmpty()
                ? java.util.EnumSet.noneOf(CrimeFlag.class) : java.util.EnumSet.copyOf(flags)));
        CrimeRecord record = record(incidentId, offender, victim, crimeId, level, effective, 0, 0, 0, context);
        return commitPrepared(CrimeWorldData.get(level.getServer()), record, () -> {},
                () -> evidence(level, offender, victim, record, effective, type.awareness(),
                        CrimeIds.ATTEMPTED_MUGGING.equals(crimeId)),
                () -> {
                    IncidentNotifications.safely(() -> CrimeIntegrationHooks.onCommitted(level.getServer(), record.view()));
                    IncidentNotifications.post(new NpcCrimeCommittedEvent(offender.getUUID(), record.victim(),
                            crimeId, incidentId, incidentId));
                });
    }

    /** A paid ransom is an audit fact; capture already applied its Karma/Heat and private effects. */
    public static Optional<CrimeRecordView> commitRansom(UUID demandId, ServerPlayer captor,
            LivingEntity victim, ServerLevel level, long amount, Runnable release) {
        if (!available(level)) return Optional.empty();
        var context = context(victim, WitnessResult.none(), "ransom");
        context.put(CrimeContext.RANSOM_ID, demandId.toString());
        CrimeRecord record = record(demandId, captor, victim, CrimeIds.EXTORTION, level,
                WitnessResult.none(), 0, 0, amount, context);
        return commitPrepared(CrimeWorldData.get(level.getServer()), record, () -> {}, release,
                () -> CrimeIntegrationHooks.onCommitted(level.getServer(), record.view()));
    }

    /**
     * Insert before consequences; reject replay/frozen stores before executing any callback.
     * Consequences are internal, nonthrowing state writes. Evidence preflights may veto evidence,
     * never the already completed act. External notifications are isolated and flushed last.
     * This is server-thread ordering, not atomic persistence across player/world save files.
     */
    public static Optional<CrimeRecordView> commitPrepared(CrimeWorldData data, CrimeRecord record,
            Runnable consequences, Runnable evidence, Runnable notifications) {
        if (!ServerMutationGate.allows(data) || !data.tryAddRecord(record)) return Optional.empty();
        try (var scope = new IncidentNotifications()) {
            consequences.run();
            IncidentNotifications.safely(evidence);
            IncidentNotifications.safely(notifications);
        }
        return Optional.of(record.view());
    }

    private static boolean available(ServerLevel level) {
        return level != null && level.getServer() != null && level.getServer().isSameThread()
                && ServerMutationGate.allows(level.getServer());
    }

    /**
     * What a masked crime costs instead of Heat: the Heat is banked against the player (or voided, if
     * the operator turned deferral off), and any responder who watched it starts hunting.
     *
     * <p>The responder test is {@code EntitySelectors.isResponder}, not {@code McaCompat.isGuard}, for
     * the same reason the stand-down scan uses it: a server that configured a non-MCA responder has
     * said that entity enforces the law, and a pursuit that only guards could start would leave those
     * servers with a mask that works perfectly against their own police.
     */
    private static void maskedConsequences(ServerPlayer offender, ServerLevel level, WitnessResult witnesses,
                                           ResourceLocation crimeId, UUID incidentId, long heat) {
        var c = McaCrimeConfig.COMMON;
        var data = dev.otectus.mcacrime.state.CrimeAttachments.get(offender);
        if (c.maskDefersHeat.get()) {
            dev.otectus.mcacrime.mask.MaskHeatLedger.defer(data.getPendingMaskedHeat(),
                    new dev.otectus.mcacrime.state.PlayerCrimeData.PendingMaskedHeat(crimeId,
                            incidentId.toString(), heat, data.getOnlineTicksLived()),
                    dev.otectus.mcacrime.state.PlayerCrimeData.MAX_DEFERRED_MASKED_INCIDENTS);
        }
        long pursuit = c.maskedPursuitTicks.get();
        if (pursuit <= 0L) {
            return;
        }
        for (UUID witnessId : witnesses.witnessIds()) {
            var witness = level.getEntity(witnessId);
            if (witness != null && dev.otectus.mcacrime.detect.EntitySelectors.isResponder(witness)) {
                data.setMaskedPursuitUntilTick(data.getOnlineTicksLived() + pursuit);
                break;
            }
        }
    }

    private static void evidence(ServerLevel level, LivingEntity offender, @Nullable LivingEntity victim,
            CrimeRecord record, WitnessResult witnesses, CrimeAwareness awareness, boolean attempt) {
        evidence(level, offender, victim, record, witnesses, awareness, attempt, level.getGameTime());
    }

    private static void evidence(ServerLevel level, LivingEntity offender, @Nullable LivingEntity victim,
            CrimeRecord record, WitnessResult witnesses, CrimeAwareness awareness, boolean attempt,
            long observedAt) {
        var observations = ObservationService.record(level, offender, victim, record.type(), record.id(),
                witnesses, awareness, observedAt);
        VictimMemoryService.record(level, offender, victim, record.type(), record.id(), awareness, attempt, observations);
    }

    private static CrimeRecord record(UUID id, LivingEntity offender, @Nullable LivingEntity victim,
            ResourceLocation crimeId, ServerLevel level, WitnessResult witnesses, long heat, long karma,
            long fine, Map<String, String> context) {
        return record(id, offender, victim, crimeId, level, witnesses, heat, karma, fine, context, null);
    }

    private static CrimeRecord record(UUID id, LivingEntity offender, @Nullable LivingEntity victim,
            ResourceLocation crimeId, ServerLevel level, WitnessResult witnesses, long heat, long karma,
            long fine, Map<String, String> context, @Nullable IncidentContext incidentContext) {
        // Preserve the existing jurisdiction rule: victim home for player crime, thief home for NPC crime.
        LivingEntity anchor = offender instanceof ServerPlayer ? victim : offender;
        OptionalInt village = anchor == null ? OptionalInt.empty() : McaCompat.getHomeVillageId(anchor);
        CrimeCommunityKey community = CrimeCommunityResolver.resolve(anchor, level).orElse(null);
        if (incidentContext != null) {
            // The typed context replaces both halves together or neither. Taking the community from the
            // property and the village integer from the victim would produce a record whose two
            // jurisdiction fields disagreed, which every downstream reader would be entitled to trust.
            CrimeCommunityKey selected = incidentContext.selected().orElse(null);
            if (selected != null) {
                community = selected;
                village = OptionalInt.of(selected.villageId());
            }
        }
        return new CrimeRecord(id, offender.getUUID(), victim == null ? null : victim.getUUID(), crimeId,
                village, community, witnesses.witnessed(), witnesses.witnessIds(), level.getGameTime(),
                heat, karma, fine, 0, Resolution.UNRESOLVED, 0, List.of(), null, context);
    }

    private static Map<String, String> context(@Nullable LivingEntity victim, WitnessResult witnesses, String detection) {
        Map<String, String> context = new LinkedHashMap<>();
        context.put(CrimeContext.DETECTION, detection);
        if (witnesses.truncated()) context.put(CrimeContext.WITNESS_COUNT_TOTAL,
                Integer.toString(witnesses.totalWitnesses()));
        if (victim != null) {
            String name = McaCompat.getVillagerDisplayName(victim).getString();
            if (!name.isEmpty()) context.put(CrimeContext.VICTIM_NAME, name);
            context.put(CrimeContext.VICTIM_ROLE, victim instanceof ServerPlayer ? "player"
                    : McaCompat.isGuard(victim) ? "guard" : McaCompat.isAdult(victim) ? "villager" : "child");
        }
        return context;
    }
}
