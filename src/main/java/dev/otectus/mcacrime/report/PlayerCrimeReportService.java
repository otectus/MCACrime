package dev.otectus.mcacrime.report;

import dev.otectus.mcacrime.action.ActionSessionManager;
import dev.otectus.mcacrime.api.model.CrimeCommunityKey;
import dev.otectus.mcacrime.captivity.CustodyRegistry;
import dev.otectus.mcacrime.compat.McaCompat;
import dev.otectus.mcacrime.config.CrimeWorldSettings;
import dev.otectus.mcacrime.detect.EntitySelectors;
import dev.otectus.mcacrime.enforcement.Jurisdictions;
import dev.otectus.mcacrime.enforcement.NpcCriminalPursuit;
import dev.otectus.mcacrime.memory.CrimeObservation;
import dev.otectus.mcacrime.memory.CrimeReport;
import dev.otectus.mcacrime.memory.ReportService;
import dev.otectus.mcacrime.mug.npc.NpcMugAbortReason;
import dev.otectus.mcacrime.mug.npc.NpcMuggingService;
import dev.otectus.mcacrime.state.world.CrimeWorldData;
import dev.otectus.mcacrime.state.world.ServerMutationGate;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraftforge.common.util.FakePlayer;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Server-authoritative player report picker, commit validation and dispatch handoff. */
public final class PlayerCrimeReportService {
    public static final int MAX_CHOICES = 20;
    private static final long SESSION_TTL = 200L;
    private static final double REACH_SQR = 16.0D;
    private static final Map<UUID, MenuSession> MENUS = new ConcurrentHashMap<>();

    public record Choice(UUID evidenceId, UUID caseId, @Nullable UUID suspectId,
                         Component suspectName, ResourceLocation incidentType, Component area,
                         long occurredAt, PlayerReportStatus status) { }

    public record Menu(UUID id, int revision, UUID responderId, long issuedAt, List<Choice> choices,
                       @Nullable String emptyReasonKey) {
        public Menu { choices = choices == null ? List.of() : List.copyOf(choices); }
    }

    public record SubmitResult(boolean accepted, @Nullable PlayerReportReceipt receipt, String messageKey) {
        static SubmitResult rejected(String key) { return new SubmitResult(false, null, key); }
        static SubmitResult accepted(PlayerReportReceipt row, String key) { return new SubmitResult(true, row, key); }
    }

    private static final class MenuSession {
        final UUID id;
        final int revision;
        final UUID reporter;
        final UUID responder;
        final ResourceLocation dimension;
        final long expiresAt;
        final Set<UUID> offered;
        final Set<UUID> nonces = new HashSet<>();

        MenuSession(UUID id, UUID reporter, UUID responder, ResourceLocation dimension,
                    long expiresAt, Set<UUID> offered) {
            this.id = id;
            this.revision = 1;
            this.reporter = reporter;
            this.responder = responder;
            this.dimension = dimension;
            this.expiresAt = expiresAt;
            this.offered = Set.copyOf(offered);
        }

        synchronized boolean claim(UUID nonce) { return nonce != null && nonces.add(nonce); }
    }

    private PlayerCrimeReportService() { }

    /** Opens choices against the live responder; used by the action and non-cheat command. */
    public static @Nullable Menu open(ServerPlayer player, LivingEntity responder) {
        if (!(player.level() instanceof ServerLevel level) || !basicPlayer(player)
                || !validResponder(player, responder) || !settingsAllow(player.getServer())) return null;
        List<Choice> choices = eligible(player, level);
        Set<UUID> offered = choices.stream().map(Choice::evidenceId)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        long now = level.getGameTime();
        MenuSession session = new MenuSession(UUID.randomUUID(), player.getUUID(), responder.getUUID(),
                level.dimension().location(), now + SESSION_TTL, offered);
        MENUS.put(player.getUUID(), session);
        String empty = choices.isEmpty() ? "mcacrime.report.empty" : null;
        return new Menu(session.id, session.revision, responder.getUUID(), now, choices, empty);
    }

    /** Non-cheat command adapter; uses the same four-block ray target and the same menu service. */
    public static int openTargetedFromCommand(ServerPlayer player) {
        LivingEntity responder = dev.otectus.mcacrime.action.CrimeActionService.rayTraceLiving(player, 4.0D);
        Menu menu = responder == null ? null : open(player, responder);
        if (menu == null) {
            player.sendSystemMessage(Component.translatable("mcacrime.report.rejected.guard_unavailable"));
            return 0;
        }
        dev.otectus.mcacrime.network.CrimeNetwork.sendReportMenu(player, menu);
        return 1;
    }

    public static int openReports(ServerPlayer player) {
        dev.otectus.mcacrime.network.CrimeNetwork.sendPlayerReports(player,
                new dev.otectus.mcacrime.network.PlayerReportsS2CPacket(reports(player)));
        return 1;
    }

    public static SubmitResult submit(ServerPlayer player, UUID menuId, int revision, UUID responderId,
                                      UUID evidenceId, UUID nonce) {
        MinecraftServer server = player == null ? null : player.getServer();
        if (!(player != null && player.level() instanceof ServerLevel level) || !basicPlayer(player)
                || !settingsAllow(server) || !ServerMutationGate.allows(server)
                || !PlayerReportData.get(server).allowsMutations(server)) {
            return SubmitResult.rejected("mcacrime.report.rejected.disabled");
        }
        MenuSession menu = MENUS.get(player.getUUID());
        if (menu == null || !menu.id.equals(menuId) || menu.revision != revision
                || !menu.reporter.equals(player.getUUID()) || !menu.responder.equals(responderId)
                || !menu.dimension.equals(level.dimension().location()) || level.getGameTime() > menu.expiresAt
                || !menu.offered.contains(evidenceId) || !menu.claim(nonce)) {
            return SubmitResult.rejected("mcacrime.report.rejected.stale");
        }
        Entity entity = level.getEntity(responderId);
        if (!(entity instanceof LivingEntity responder) || !validResponder(player, responder)) {
            return SubmitResult.rejected("mcacrime.report.rejected.guard_unavailable");
        }
        CrimeWorldData world = CrimeWorldData.get(server);
        PlayerReportData reportData = PlayerReportData.get(server);
        CrimeObservation evidence = world.observation(evidenceId).orElse(null);
        if (!eligibleAtCommit(player, evidence, level, menu) || reportData.reported(player.getUUID(), evidenceId)) {
            return SubmitResult.rejected("mcacrime.report.rejected.evidence_unavailable");
        }

        var crimeCase = world.recordById(evidence.incidentId()).orElse(null);
        ThreatReceipt threat = reportData.threat(evidence.incidentId()).orElse(null);
        boolean liveThreat = threat != null && threat.outcome() == ThreatReceipt.Outcome.ACTIVE;
        if ((crimeCase == null && !liveThreat) || (crimeCase != null && !crimeCase.actionable())) {
            return SubmitResult.rejected("mcacrime.report.rejected.case_closed");
        }

        CrimeCommunityKey caseAuthority = crimeCase == null ? threat.caseAuthority()
                : crimeCase.communityKey().orElse(null);
        CrimeCommunityKey responderAuthority = ReportService.jurisdictionOf(level, responder);
        boolean global = dev.otectus.mcacrime.McaCrimeConfig.COMMON.globalCrimePropagation.get();
        if (caseAuthority != null && !global && !caseAuthority.equals(responderAuthority)) {
            return SubmitResult.rejected("mcacrime.report.rejected.wrong_jurisdiction");
        }
        CrimeCommunityKey receiving = caseAuthority == null ? responderAuthority : caseAuthority;
        PlayerReportData.ReportReservation reservation = reportData.reserveReport(server, player.getUUID(),
                evidenceId, row -> reclaimable(server, row, level.getGameTime())).orElse(null);
        if (reservation == null) return SubmitResult.rejected("mcacrime.report.rejected.capacity");

        ReportService.PreparedPlayerReport prepared = ReportService.preparePlayer(level, player, responder,
                evidence, receiving).orElse(null);
        if (prepared == null) {
            reportData.cancelReservation(reservation);
            return SubmitResult.rejected("mcacrime.report.rejected.event");
        }

        // All ordinary rejection points and the cancellable hook have passed. Only now may the report
        // interrupt a threat; its shared abort path finalizes the captured receipt exactly once.
        if (liveThreat) {
            UUID selectedCaseId = evidence.incidentId();
            NpcMuggingService.sessionFor(player.getUUID()).ifPresent(session -> {
                if (session.transactionId().equals(selectedCaseId))
                    NpcMuggingService.abort(player.getUUID(), NpcMugAbortReason.GUARD_INTERVENTION);
            });
            evidence = world.observation(evidenceId).orElse(evidence);
            crimeCase = world.recordById(evidence.incidentId()).orElse(null);
        }
        if (crimeCase == null || !crimeCase.actionable()) {
            reportData.cancelReservation(reservation);
            return SubmitResult.rejected("mcacrime.report.rejected.case_closed");
        }
        CrimeReport filed = ReportService.commitPlayer(level, player, evidence, prepared).orElse(null);
        if (filed == null) {
            reportData.cancelReservation(reservation);
            return SubmitResult.rejected("mcacrime.report.rejected.event");
        }

        PlayerReportReceipt.Dispatch dispatch = filed.supportsArrest(
                dev.otectus.mcacrime.McaCrimeConfig.COMMON.reportConfidenceThreshold.get())
                ? PlayerReportDispatchService.tryDispatch(level, responder, filed, evidence, receiving)
                        || NpcCriminalPursuit.isPursued(filed.suspectId())
                    ? PlayerReportReceipt.Dispatch.SEARCHING : PlayerReportReceipt.Dispatch.AWAITING_GUARD
                : PlayerReportReceipt.Dispatch.RECORDED;
        long nextDispatchAt = dispatch == PlayerReportReceipt.Dispatch.SEARCHING
                ? level.getGameTime() + dev.otectus.mcacrime.McaCrimeConfig.COMMON.guardThiefPursuitTimeoutTicks.get()
                : level.getGameTime();
        PlayerReportReceipt receipt = new PlayerReportReceipt(filed.reportId(), player.getUUID(), evidenceId,
                filed.incidentId(), filed.suspectId(), responderId, receiving, level.getGameTime(), dispatch,
                nextDispatchAt);
        if (!reportData.addReport(server, receipt, reservation)) {
            reportData.cancelReservation(reservation);
            return SubmitResult.rejected("mcacrime.report.rejected.already_filed");
        }
        MENUS.remove(player.getUUID(), menu);
        String outcome = dispatch == PlayerReportReceipt.Dispatch.SEARCHING
                ? "mcacrime.report.accepted.responding"
                : dispatch == PlayerReportReceipt.Dispatch.AWAITING_GUARD
                ? "mcacrime.report.accepted.no_guard"
                : "mcacrime.report.accepted.unidentified";
        player.sendSystemMessage(Component.translatable(outcome));
        return SubmitResult.accepted(receipt, outcome);
    }

    public static List<Choice> reports(ServerPlayer player) {
        if (player == null || player.getServer() == null) return List.of();
        MinecraftServer server = player.getServer();
        CrimeWorldData world = CrimeWorldData.get(server);
        List<Choice> rows = new ArrayList<>();
        for (PlayerReportReceipt receipt : PlayerReportData.get(server).reportsBy(player.getUUID())) {
            CrimeObservation evidence = world.observation(receipt.evidenceId()).orElse(null);
            if (evidence == null) continue;
            rows.add(choice(player, evidence, status(server, receipt)));
        }
        return List.copyOf(rows);
    }

    /** Public/news helper: only an accepted identified report received by this authority qualifies. */
    public static boolean acceptedIdentified(MinecraftServer server, UUID caseId,
                                             @Nullable CrimeCommunityKey receivingAuthority, long now) {
        if (server == null || caseId == null) return false;
        var record = CrimeWorldData.get(server).recordById(caseId).orElse(null);
        if (record == null) return false;
        return CrimeWorldData.get(server).reportsAgainst(record.offender()).stream().anyMatch(report ->
                report.incidentId().equals(caseId) && report.suspectId() != null && !report.expired(now)
                        && java.util.Objects.equals(report.jurisdiction(), receivingAuthority)
                        && report.supportsArrest(dev.otectus.mcacrime.McaCrimeConfig.COMMON.reportConfidenceThreshold.get()));
    }

    public static void forget(UUID player) { if (player != null) MENUS.remove(player); }

    /** Invalidates only transient offers after reports/observations are disabled; receipts remain. */
    public static void clearOffers() { MENUS.clear(); }

    private static List<Choice> eligible(ServerPlayer player, ServerLevel level) {
        CrimeWorldData world = CrimeWorldData.get(level.getServer());
        PlayerReportData reports = PlayerReportData.get(level.getServer());
        long now = level.getGameTime();
        return world.observationsBy(player.getUUID()).stream()
                .filter(CrimeObservation::pending).filter(row -> !row.expired(now))
                .filter(row -> !reports.reported(player.getUUID(), row.observationId()))
                .filter(row -> world.recordById(row.incidentId()).map(record -> record.actionable()).orElse(false)
                        || reports.threat(row.incidentId()).map(threat -> threat.outcome() == ThreatReceipt.Outcome.ACTIVE)
                        .orElse(false))
                .sorted(Comparator.comparingLong(CrimeObservation::observedAt).reversed())
                .limit(MAX_CHOICES).map(row -> choice(player, row, PlayerReportStatus.ELIGIBLE)).toList();
    }

    private static Choice choice(ServerPlayer viewer, CrimeObservation evidence, PlayerReportStatus status) {
        Entity suspect = evidence.suspectedActorId() == null ? null
                : ((ServerLevel) viewer.level()).getEntity(evidence.suspectedActorId());
        ThreatReceipt threat = viewer.getServer() == null ? null
                : PlayerReportData.get(viewer.getServer()).threat(evidence.incidentId()).orElse(null);
        Component name = suspect instanceof LivingEntity living
                ? Component.literal(boundedPlain(McaCompat.getVillagerDisplayName(living), 64))
                : evidence.suspectedActorId() == null ? Component.translatable("mcacrime.report.unidentified")
                : threat != null && !threat.perceivedName().isEmpty() ? Component.literal(threat.perceivedName())
                : Component.translatable("mcacrime.report.identified_thief");
        CrimeCommunityKey community = viewer.getServer() == null ? null : CrimeWorldData.get(viewer.getServer())
                .recordById(evidence.incidentId()).flatMap(record -> record.communityKey()).orElse(null);
        return new Choice(evidence.observationId(), evidence.incidentId(), evidence.suspectedActorId(), name,
                evidence.actionId(), Component.literal(boundedPlain(
                Jurisdictions.label(viewer.getServer(), community), 96)), evidence.observedAt(), status);
    }

    private static PlayerReportStatus status(MinecraftServer server, PlayerReportReceipt receipt) {
        CrimeWorldData world = CrimeWorldData.get(server);
        var record = world.recordById(receipt.caseId()).orElse(null);
        if (record == null) return PlayerReportStatus.EXPIRED;
        if (!record.actionable()) return record.resolution() == dev.otectus.mcacrime.ledger.Resolution.SERVED
                ? PlayerReportStatus.SENTENCE_COMPLETED : PlayerReportStatus.CASE_CLOSED;
        var custody = receipt.suspectId() == null ? null : world.getCustody(receipt.suspectId());
        if (custody != null && custody.isLawful() && record.sentenceId() != null
                && record.sentenceId().equals(custody.getSentenceId())) return PlayerReportStatus.IN_CUSTODY;
        return switch (receipt.dispatch()) {
            case SEARCHING -> PlayerReportStatus.SEARCHING;
            case AWAITING_GUARD -> PlayerReportStatus.AWAITING_GUARD;
            case RECORDED -> PlayerReportStatus.ACCEPTED;
        };
    }

    private static boolean eligibleAtCommit(ServerPlayer player, CrimeObservation evidence,
                                            ServerLevel level, MenuSession menu) {
        return evidence != null && evidence.observerId().equals(player.getUUID()) && evidence.pending()
                && !evidence.expired(level.getGameTime()) && menu.offered.contains(evidence.observationId());
    }

    private static boolean basicPlayer(ServerPlayer player) {
        if (player == null || player instanceof FakePlayer || !player.isAlive() || player.isSpectator()
                || player.getServer() == null || CustodyRegistry.isCaptive(player.getServer(), player.getUUID()))
            return false;
        var physical = CrimeWorldData.get(player.getServer()).physicalRestraint(player.getUUID());
        return (physical == null || !physical.restrained())
                && ActionSessionManager.forActor(player.getUUID()).isEmpty();
    }

    private static boolean reclaimable(MinecraftServer server, PlayerReportReceipt receipt, long now) {
        CrimeWorldData world = CrimeWorldData.get(server);
        var record = world.recordById(receipt.caseId()).orElse(null);
        if (record == null || !record.actionable()) return true;
        return world.reportsAgainst(receipt.suspectId()).stream()
                .filter(report -> report.reportId().equals(receipt.reportId()))
                .findFirst().map(report -> report.expired(now)).orElse(true);
    }

    private static boolean validResponder(ServerPlayer player, LivingEntity responder) {
        return responder != null && responder.isAlive() && responder.level() == player.level()
                && EntitySelectors.isAvailableResponder(responder) && player.distanceToSqr(responder) <= REACH_SQR
                && player.hasLineOfSight(responder) && !CustodyRegistry.isCaptive(player.getServer(), responder.getUUID());
    }

    private static boolean settingsAllow(MinecraftServer server) {
        if (server == null) return false;
        CrimeWorldSettings settings = CrimeWorldSettings.resolve(server);
        return settings.playerReports() && settings.observations();
    }

    private static String boundedPlain(Component component, int limit) {
        String text = component == null ? "" : component.getString();
        return text.substring(0, Math.min(Math.max(0, limit), text.length()));
    }
}
