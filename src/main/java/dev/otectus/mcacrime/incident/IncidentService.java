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
        if (!available(level) || offender == null || offender.getServer() != level.getServer()) return Optional.empty();
        var type = CrimeTypeRegistry.getOrBuiltin(crimeId).orElse(null);
        if (type == null) return Optional.empty();
        CrimeAwareness awareness = "mug_attempt".equals(detection) ? CrimeAwareness.robbery() : type.awareness();
        WitnessResult effective = "command".equals(detection) || "jailbreak".equals(detection)
                ? witnesses == null ? WitnessResult.none() : witnesses
                : WitnessChecker.resolve(level, offender, victim, awareness);
        var c = McaCrimeConfig.COMMON;
        long karma = CrimeDetector.karmaFor(type, effective.witnessed(), c.unwitnessedKarmaFactor.get());
        long heat = CrimeDetector.heatFor(type, effective.witnessed(), c.requireWitnessForHeat.get());
        Map<String, String> context = context(victim, effective, detection);
        context.putAll(provenance);
        CrimeRecord record = record(incidentId, offender, victim, crimeId, level, effective,
                heat, karma, 0, context);
        return commitPrepared(CrimeWorldData.get(level.getServer()), record,
                () -> CrimeState.applyIncident(offender, karma, heat, crimeId, incidentId.toString()),
                () -> evidence(level, offender, victim, record, effective, awareness, "mug_attempt".equals(detection)),
                () -> {
                    IncidentNotifications.safely(() -> CrimeIntegrationHooks.onCommitted(level.getServer(), record.view()));
                    if (effective.witnessed()) IncidentNotifications.post(new CrimeWitnessedEvent(offender,
                            crimeId, record.victim(), effective.totalWitnesses(), effective.witnessIds()));
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

    private static void evidence(ServerLevel level, LivingEntity offender, @Nullable LivingEntity victim,
            CrimeRecord record, WitnessResult witnesses, CrimeAwareness awareness, boolean attempt) {
        var observations = ObservationService.record(level, offender, victim, record.type(), record.id(),
                witnesses, awareness);
        VictimMemoryService.record(level, offender, victim, record.type(), record.id(), awareness, attempt, observations);
    }

    private static CrimeRecord record(UUID id, LivingEntity offender, @Nullable LivingEntity victim,
            ResourceLocation crimeId, ServerLevel level, WitnessResult witnesses, long heat, long karma,
            long fine, Map<String, String> context) {
        // Preserve the existing jurisdiction rule: victim home for player crime, thief home for NPC crime.
        LivingEntity anchor = offender instanceof ServerPlayer ? victim : offender;
        OptionalInt village = anchor == null ? OptionalInt.empty() : McaCompat.getHomeVillageId(anchor);
        CrimeCommunityKey community = CrimeCommunityResolver.resolve(anchor, level).orElse(null);
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
