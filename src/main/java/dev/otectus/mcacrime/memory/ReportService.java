package dev.otectus.mcacrime.memory;

import dev.otectus.mcacrime.McaCrime;
import dev.otectus.mcacrime.McaCrimeConfig;
import dev.otectus.mcacrime.api.event.CrimeReportEvent;
import dev.otectus.mcacrime.api.model.CrimeCommunityKey;
import dev.otectus.mcacrime.compat.McaCompat;
import dev.otectus.mcacrime.detect.CrimeCommunityResolver;
import dev.otectus.mcacrime.detect.EntitySelectors;
import dev.otectus.mcacrime.dialogue.CrimeDialogueService;
import dev.otectus.mcacrime.dialogue.DialogueEvents;
import dev.otectus.mcacrime.enforcement.ActiveIncidentRegistry;
import dev.otectus.mcacrime.enforcement.NpcCriminalPursuit;
import dev.otectus.mcacrime.job.WorldCriminalJobService;
import dev.otectus.mcacrime.ledger.CrimeFlag;
import dev.otectus.mcacrime.state.world.CrimeWorldData;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.neoforged.neoforge.common.NeoForge;

import org.jetbrains.annotations.Nullable;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Turns observations into reports (spec §12.3/§12.4).
 *
 * <p>The distinction this class exists to enforce is that <b>seeing a crime is not the same as the law
 * knowing about it</b>. Before reports, a guard on the other side of the village reacted to Heat the
 * instant a crime was committed, which is why guards read as omniscient. Now a civilian has to reach
 * somebody, and until they do the crime is something one frightened villager knows and nobody else.
 *
 * <p>Heat is not applied here, and that is deliberate rather than an omission. The crime's own commit
 * already charged whatever the crime type is worth; charging again when the report lands would mean a
 * crime committed in front of a crowd costs several times what the same crime costs in an alley, for
 * reasons the player is given no way to see. What filing changes is <em>jurisdiction</em>: which
 * village's guards have a basis to act, and whose standing drops.
 */
public final class ReportService {

    private ReportService() {
    }

    /**
     * Files a responder's own direct observation immediately (§12.3 step 1).
     *
     * <p>A guard who watched it happen has nobody to walk to, so there is no pending state for this
     * one — it is authoritative the moment it exists.
     */
    public static Optional<CrimeReport> fileDirect(ServerLevel level, LivingEntity responder,
                                                   CrimeObservation observation) {
        if (!dev.otectus.mcacrime.ai.NpcAwareness.isAwake(responder)) return Optional.empty();
        return file(level, responder, observation, true);
    }

    /**
     * Delivers a civilian's pending observation to a responder they have reached (§12.3 steps 2–4).
     * Called by the reaction controller when a {@code SEEKING_HELP} villager arrives.
     *
     * @param responder who they reached; null means the responder moved off before delivery, in which
     *                  case nothing is filed and the observation stays pending
     */
    public static Optional<CrimeReport> deliver(ServerLevel level, LivingEntity reporter,
                                                @Nullable LivingEntity responder, @Nullable UUID observationId) {
        MinecraftServer server = level == null ? null : level.getServer();
        if (server == null || responder == null || observationId == null) {
            return Optional.empty();
        }
        CrimeObservation observation = CrimeWorldData.get(server).observation(observationId).orElse(null);
        if (observation == null || !observation.pending() || observation.expired(level.getGameTime())
                || !observation.observerId().equals(reporter.getUUID()) || !dev.otectus.mcacrime.ai.NpcAwareness.isAwake(reporter)
                || !dev.otectus.mcacrime.ai.NpcAwareness.isAwake(responder) || responder.level() != level || reporter.level() != level
                || !EntitySelectors.isResponder(responder) || reporter.distanceToSqr(responder) > 9
                || !reporter.hasLineOfSight(responder) || dataSuppressed(server, reporter)) {
            return Optional.empty();
        }
        Optional<CrimeReport> filed = file(level, reporter, observation, false);
        filed.ifPresent(report -> pursueCriminalSuspect(level, responder, report, observation));
        return filed;
    }

    /**
     * The "guard becomes aware after completion" path of spec §"Guard intervention".
     *
     * <p>A guard that watched the threat is handled by the active-incident scan; this is the other
     * half -- a victim or a witness walks up afterwards and says who did it, and the guard goes after
     * the offender on the strength of the report rather than of anything it saw. The synthetic
     * incident it engages with deliberately carries no {@code CAUGHT_IN_ACT}: the guard did not catch
     * this one in the act, and the mandatory-custody branch must not be reached by hearsay.
     */
    private static void pursueCriminalSuspect(ServerLevel level, LivingEntity responder, CrimeReport report,
                                              CrimeObservation observation) {
        MinecraftServer server = level.getServer();
        if (server == null || !EntitySelectors.isResponder(responder)) {
            return;
        }
        UUID suspectId = report.suspectId();
        if (!report.supportsArrest(McaCrimeConfig.COMMON.reportConfidenceThreshold.get())
                || suspectId == null || !WorldCriminalJobService.of(server).isCriminal(suspectId)) {
            return; // an ordinary villager is not chased for having been named
        }
        if (!(level.getEntity(suspectId) instanceof LivingEntity suspect) || !suspect.isAlive()) {
            return;
        }
        NpcCriminalPursuit.engage(level, responder, new ActiveIncidentRegistry.ActiveIncident(
                report.reportId(), suspectId, observation.victimId(), level.dimension(),
                level.getGameTime(), EnumSet.of(CrimeFlag.NPC_OFFENDER),
                ActiveIncidentRegistry.Phase.COMMITTED));
    }

    /**
     * The shared filing path.
     *
     * @param authoritative true when the reporter observed it themselves in a responder role
     */
    private static Optional<CrimeReport> file(ServerLevel level, LivingEntity reporter,
                                              CrimeObservation observation, boolean authoritative) {
        MinecraftServer server = level == null ? null : level.getServer();
        if (!dev.otectus.mcacrime.state.world.ServerMutationGate.allows(server) || observation == null || !observation.pending()
                || observation.expired(level.getGameTime()) || !observation.observerId().equals(reporter.getUUID())
                || dataSuppressed(server, reporter)
                || (authoritative && (!EntitySelectors.isResponder(reporter) || !observation.sawAct()))) {
            return Optional.empty();
        }
        CrimeWorldData data = CrimeWorldData.get(server);
        long now = level.getGameTime();
        CrimeCommunityKey jurisdiction = jurisdictionFor(level, observation);
        UUID reportId = UUID.randomUUID();

        CrimeReportEvent.Pre pre = new CrimeReportEvent.Pre(reportId, observation.incidentId(),
                observation.observationId(), reporter.getUUID(), observation.suspectedActorId(),
                observation.actionId(), jurisdiction, observation.confidence(), authoritative);
        if (NeoForge.EVENT_BUS.post(pre).isCanceled()) {
            // Suppressed — intimidation, a bribe, a corrupt jurisdiction. Exactly one report is stopped
            // (§12.3 step 6); every other observation of the same incident is untouched and can still
            // be filed by somebody else.
            data.replaceObservation(observation.withReportState(ReportState.SUPPRESSED));
            return Optional.empty();
        }

        CrimeReport report = new CrimeReport(reportId, observation.incidentId(), observation.observationId(),
                reporter.getUUID(), observation.suspectedActorId(), observation.actionId(), jurisdiction,
                now, now + McaCrimeConfig.COMMON.observationStatuteTicks.get(),
                observation.confidence(), authoritative);
        if (!data.addReport(report)) {
            return Optional.empty();
        }
        data.replaceObservation(observation.withReportState(ReportState.FILED));
        propagate(server, report);

        dev.otectus.mcacrime.incident.IncidentNotifications.post(new CrimeReportEvent.Post(report.reportId(), report.incidentId(),
                report.observationId(), report.reporterId(), report.suspectId(), report.actionId(),
                report.jurisdiction(), report.confidence(), report.authoritative()));

        ServerPlayer suspect = report.suspectId() == null ? null : server.getPlayerList().getPlayer(report.suspectId());
        if (suspect != null && !authoritative) {
            CrimeDialogueService.speak(reporter, suspect, DialogueEvents.REPORT_FILED,
                    CrimeDialogueService.context(level, reporter, suspect, report.incidentId(),
                            DialogueEvents.REPORT_FILED));
        }
        if (suspect != null && report.supportsArrest(McaCrimeConfig.COMMON.reportConfidenceThreshold.get()))
            dev.otectus.mcacrime.enforcement.GuardEnforcement.alert(suspect, "mcacrime.msg.guardaggro.reported", 600L);
        return Optional.of(report);
    }

    /**
     * Which community receives the report (§12.4): the victim's home village if there is one, otherwise
     * the reporter's, otherwise the wilderness.
     *
     * <p>The reporter's village is the second choice rather than the first because the crime belongs to
     * where it happened to somebody, not to whoever happened to carry the news. A traveller reporting a
     * roadside robbery to their own village does not thereby move the crime into it.
     */
    @Nullable
    private static CrimeCommunityKey jurisdictionFor(ServerLevel level, CrimeObservation observation) {
        var recorded = CrimeWorldData.get(level.getServer()).recordById(observation.incidentId());
        if (recorded.isPresent()) return recorded.get().communityKey().orElse(null);
        if (observation.victimId() != null) {
            var victim = level.getEntity(observation.victimId());
            CrimeCommunityKey byVictim = CrimeCommunityResolver.resolve(victim, level).orElse(null);
            if (byVictim != null) {
                return byVictim;
            }
        }
        var observer = level.getEntity(observation.observerId());
        return CrimeCommunityResolver.resolve(observer, level).orElse(null);
    }

    /**
     * Applies the standing consequence of a filed report, and implements {@code globalCrimePropagation}
     * — a setting that shipped in 0.1.0 and until now was read by nothing at all.
     *
     * <p>Default is strictly local: only the receiving jurisdiction's opinion changes, which is the
     * whole point of per-village standing. With the setting on, every community that already knows the
     * player hears about it too, which is the "one crime sours every village" behaviour the key has
     * always described.
     *
     * <p>The drop here is a report consequence and is intentionally smaller than the commit-time
     * penalty in {@code RelationshipConsequences}: being seen is one cost, being formally accused is
     * another, and they are not the same event.
     */
    private static void propagate(MinecraftServer server, CrimeReport report) {
        if (!report.supportsArrest(McaCrimeConfig.COMMON.reportConfidenceThreshold.get())) return;
        CrimeWorldData world = CrimeWorldData.get(server);
        if (world.reportsAgainst(report.suspectId()).stream()
                .filter(r -> r.incidentId().equals(report.incidentId()) && r.supportsArrest(McaCrimeConfig.COMMON.reportConfidenceThreshold.get()))
                .count() > 1) return;
        world.recordById(report.incidentId()).ifPresent(record ->
                dev.otectus.mcacrime.integration.CrimeIntegrationHooks.onCommitted(server, record.view()));
        if (dev.otectus.mcacrime.integration.CrimeIntegrationHooks.willRecordCanonically(report.actionId())) return;
        int drop = McaCrimeConfig.COMMON.villageRepDrop.get();
        if (drop <= 0) {
            return;
        }
        CrimeWorldData data = CrimeWorldData.get(server);
        int reportDrop = Math.max(1, drop / 2);
        if (report.jurisdiction() != null) {
            data.addReputation(report.jurisdiction(), report.suspectId(), -reportDrop);
        }
        if (!McaCrimeConfig.COMMON.globalCrimePropagation.get()) {
            return;
        }
        for (CrimeCommunityKey community : data.communities()) {
            if (!community.equals(report.jurisdiction())) {
                data.addReputation(community, report.suspectId(), -reportDrop);
            }
        }
    }

    // ------------------------------------------------------------------ queries used by enforcement

    /**
     * Reports against this suspect that a guard in {@code jurisdiction} may act on. A guard never sees
     * another village's reports unless global propagation is on — that restriction is what stops a
     * guard three hundred blocks away from arresting somebody for a crime nobody told them about.
     */
    public static List<CrimeReport> actionableAgainst(MinecraftServer server, UUID suspect,
                                                      @Nullable CrimeCommunityKey jurisdiction, long now) {
        if (server == null || suspect == null) {
            return List.of();
        }
        boolean global = McaCrimeConfig.COMMON.globalCrimePropagation.get();
        return CrimeWorldData.get(server).reportsAgainst(suspect).stream()
                .filter(report -> !report.expired(now))
                .filter(report -> hasActionableCase(CrimeWorldData.get(server), report))
                .filter(report -> global || (jurisdiction != null && jurisdiction.equals(report.jurisdiction())))
                .toList();
    }

    /** Whether any report against this suspect is strong enough to justify an arrest here and now. */
    public static boolean warrantExists(MinecraftServer server, UUID suspect,
                                        @Nullable CrimeCommunityKey jurisdiction, long now) {
        double threshold = McaCrimeConfig.COMMON.reportConfidenceThreshold.get();
        for (CrimeReport report : actionableAgainst(server, suspect, jurisdiction, now)) {
            if (report.supportsArrest(threshold)) {
                return true;
            }
        }
        return false;
    }

    /** A report carries evidence for one live case; it cannot outlive resolution or name another crime. */
    public static boolean hasActionableCase(CrimeWorldData data, CrimeReport report) {
        return data != null && report != null && report.suspectId() != null
                && data.recordById(report.incidentId()).filter(record -> record.actionable()
                && record.offender().equals(report.suspectId()) && record.type().equals(report.actionId()))
                .isPresent();
    }

    public static boolean knownCase(MinecraftServer server, dev.otectus.mcacrime.ledger.CrimeRecord record,
                                     @Nullable CrimeCommunityKey jurisdiction, long now) {
        if (record == null || !record.actionable()) return false;
        return dev.otectus.mcacrime.justice.JusticeService.evaluate(server, record.offender(), jurisdiction, now)
                .caseIds().contains(record.id());
    }

    /** The community a guard is enforcing for, from its own home village. */
    @Nullable
    public static CrimeCommunityKey jurisdictionOf(ServerLevel level, LivingEntity responder) {
        if (responder == null || !McaCompat.isMcaVillager(responder)) {
            return null;
        }
        return CrimeCommunityResolver.resolve(responder, level).orElse(null);
    }

    /**
     * Ages out stale observations and reports. Called on a bounded interval by the decay handler, never
     * per tick.
     */
    public static void prune(MinecraftServer server, long now) {
        if (server == null) {
            return;
        }
        int changed = CrimeWorldData.get(server).pruneObservations(now);
        if (changed > 0) {
            McaCrime.LOGGER.debug("MCA: Crime aged out {} observation(s)/report(s).", changed);
        }
    }

    private static boolean dataSuppressed(MinecraftServer server, LivingEntity reporter) {
        return CrimeWorldData.get(server).isCaptive(reporter.getUUID()) || !McaCompat.isAdult(reporter)
                || McaCompat.isVillagerSleeping(reporter);
    }

}
