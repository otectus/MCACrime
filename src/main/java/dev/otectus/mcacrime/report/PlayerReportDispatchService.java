package dev.otectus.mcacrime.report;

import dev.otectus.mcacrime.McaCrime;
import dev.otectus.mcacrime.McaCrimeConfig;
import dev.otectus.mcacrime.detect.EntitySelectors;
import dev.otectus.mcacrime.enforcement.NpcCriminalPursuit;
import dev.otectus.mcacrime.enforcement.ResponderAssignments;
import dev.otectus.mcacrime.memory.CrimeReport;
import dev.otectus.mcacrime.memory.ReportService;
import dev.otectus.mcacrime.ledger.CrimeRecord;
import dev.otectus.mcacrime.state.world.CrimeWorldData;
import dev.otectus.mcacrime.state.world.ServerMutationGate;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import javax.annotation.Nullable;
import java.util.LinkedHashSet;
import java.util.UUID;

/** Bounded retry pump for accepted reports whose first responder could not take the pursuit. */
@Mod.EventBusSubscriber(modid = McaCrime.MOD_ID)
public final class PlayerReportDispatchService {
    private static final int INTERVAL = 20;
    private static final int BUDGET = 8;
    private static int ticks;

    private PlayerReportDispatchService() { }

    @SubscribeEvent
    public static void tick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || ++ticks < INTERVAL) return;
        ticks = 0;
        var server = event.getServer();
        if (!ServerMutationGate.allows(server)) return;
        PlayerReportData reportData = PlayerReportData.get(server);
        if (!reportData.allowsMutations(server)) return;
        long now = server.overworld().getGameTime();
        reportData.prune(server, now);
        CrimeWorldData world = CrimeWorldData.get(server);
        for (PlayerReportReceipt receipt : reportData.pendingDispatches(now, BUDGET)) {
            CrimeReport canonical = world.reportsAgainst(receipt.suspectId()).stream()
                    .filter(report -> report.reportId().equals(receipt.reportId())).findFirst().orElse(null);
            CrimeRecord crimeCase = world.recordById(receipt.caseId()).orElse(null);
            if (canonical == null || canonical.expired(now) || crimeCase == null
                    || !ReportService.hasActionableCase(world, canonical)
                    || lawfullyHeldForCase(world, receipt)) continue;
            if (receipt.suspectId() == null || NpcCriminalPursuit.isPursued(receipt.suspectId())) {
                if (receipt.suspectId() != null) reportData.replaceReport(server,
                        receipt.withDispatch(PlayerReportReceipt.Dispatch.SEARCHING, now + pursuitTimeout()));
                continue;
            }
            LocatedSuspect located = locate(server, crimeCase, receipt.suspectId());
            if (located == null) {
                reportData.replaceReport(server, receipt.withDispatch(
                        PlayerReportReceipt.Dispatch.AWAITING_GUARD, now + INTERVAL));
                continue;
            }
            LivingEntity responder = nearest(located.level(), located.suspect(), receipt);
            boolean searching = responder != null && tryDispatch(located.level(), responder, canonical, crimeCase,
                    receipt.receivingAuthority());
            reportData.replaceReport(server, receipt.withDispatch(searching
                    ? PlayerReportReceipt.Dispatch.SEARCHING : PlayerReportReceipt.Dispatch.AWAITING_GUARD,
                    now + (searching ? pursuitTimeout() : INTERVAL)));
        }
    }

    @SubscribeEvent
    public static void logout(PlayerEvent.PlayerLoggedOutEvent event) {
        PlayerCrimeReportService.forget(event.getEntity().getUUID());
    }

    @SubscribeEvent
    public static void stopping(ServerStoppingEvent event) {
        ticks = 0;
        PlayerCrimeReportService.clearOffers();
    }

    @Nullable
    private static LivingEntity nearest(ServerLevel level, LivingEntity suspect, PlayerReportReceipt receipt) {
        double radius = McaCrimeConfig.COMMON.guardThiefResponseRadius.get();
        LivingEntity best = null;
        double bestDistance = Double.MAX_VALUE;
        for (LivingEntity candidate : level.getEntitiesOfClass(LivingEntity.class,
                new AABB(suspect.blockPosition()).inflate(radius), entity -> entity != suspect
                        && EntitySelectors.isAvailableResponder(entity))) {
            if ((receipt.receivingAuthority() == null && !candidate.getUUID().equals(receipt.responderId()))
                    || !java.util.Objects.equals(ReportService.jurisdictionOf(level, candidate), receipt.receivingAuthority())
                    || ResponderAssignments.isEscorting(level.getServer(), candidate.getUUID(), suspect.getUUID())
                    || NpcCriminalPursuit.isAssignedElsewhere(candidate.getUUID(), suspect.getUUID())
                    || !dev.otectus.mcacrime.ai.NpcAwareness.canSeeNow(candidate, suspect)) continue;
            double distance = candidate.distanceToSqr(suspect);
            if (distance < bestDistance) { best = candidate; bestDistance = distance; }
        }
        return best;
    }

    /** Shared first-attempt gate: a filed report cannot dispatch a distant loaded guard. */
    public static boolean tryDispatch(ServerLevel level, LivingEntity responder, CrimeReport report,
                                      dev.otectus.mcacrime.memory.CrimeObservation observation,
                                      @Nullable dev.otectus.mcacrime.api.model.CrimeCommunityKey authority) {
        if (level == null || report == null || observation == null) return false;
        CrimeRecord crimeCase = level.getServer() == null ? null : CrimeWorldData.get(level.getServer())
                .recordById(report.incidentId()).orElse(null);
        return tryDispatch(level, responder, report, crimeCase, authority);
    }

    /** Retry path uses the durable accepted report and case; the filing observation may have expired. */
    public static boolean tryDispatch(ServerLevel level, LivingEntity responder, CrimeReport report,
                                      CrimeRecord crimeCase,
                                      @Nullable dev.otectus.mcacrime.api.model.CrimeCommunityKey authority) {
        if (level == null || responder == null || report == null || crimeCase == null
                || !ServerMutationGate.allows(level.getServer())
                || report.suspectId() == null || !EntitySelectors.isAvailableResponder(responder)
                || !java.util.Objects.equals(ReportService.jurisdictionOf(level, responder), authority)
                || !(level.getEntity(report.suspectId()) instanceof LivingEntity suspect) || !suspect.isAlive()
                || responder == suspect || ResponderAssignments.isEscorting(level.getServer(),
                responder.getUUID(), suspect.getUUID())
                || NpcCriminalPursuit.isAssignedElsewhere(responder.getUUID(), suspect.getUUID())) return false;
        double radius = McaCrimeConfig.COMMON.guardThiefResponseRadius.get();
        if (responder.distanceToSqr(suspect) > radius * radius
                || !dev.otectus.mcacrime.ai.NpcAwareness.canSeeNow(responder, suspect)) return false;
        ReportService.pursueCriminalSuspect(level, responder, report, crimeCase);
        return NpcCriminalPursuit.isPursued(report.suspectId());
    }

    private record LocatedSuspect(ServerLevel level, LivingEntity suspect) { }

    @Nullable
    private static LocatedSuspect locate(net.minecraft.server.MinecraftServer server, CrimeRecord crimeCase,
                                         UUID suspectId) {
        if (server == null || crimeCase == null || suspectId == null) return null;
        LinkedHashSet<ServerLevel> levels = new LinkedHashSet<>();
        String recordedDimension = crimeCase.context().get(dev.otectus.mcacrime.ledger.CrimeContext.INCIDENT_DIMENSION);
        net.minecraft.resources.ResourceLocation parsed = recordedDimension == null ? null
                : net.minecraft.resources.ResourceLocation.tryParse(recordedDimension);
        if (parsed != null) {
            ServerLevel recorded = server.getLevel(ResourceKey.create(Registries.DIMENSION, parsed));
            if (recorded != null) levels.add(recorded);
        }
        crimeCase.communityKey().map(dev.otectus.mcacrime.api.model.CrimeCommunityKey::dimension)
                .map(id -> server.getLevel(ResourceKey.create(Registries.DIMENSION, id)))
                .ifPresent(levels::add);
        server.getAllLevels().forEach(levels::add);
        for (ServerLevel level : levels) {
            if (level.getEntity(suspectId) instanceof LivingEntity suspect && suspect.isAlive())
                return new LocatedSuspect(level, suspect);
        }
        return null;
    }

    private static boolean lawfullyHeldForCase(CrimeWorldData world, PlayerReportReceipt receipt) {
        if (receipt.suspectId() == null) return false;
        var record = world.recordById(receipt.caseId()).orElse(null);
        var custody = world.getCustody(receipt.suspectId());
        return record != null && custody != null && custody.isLawful() && record.sentenceId() != null
                && record.sentenceId().equals(custody.getSentenceId());
    }

    private static long pursuitTimeout() {
        return McaCrimeConfig.COMMON.guardThiefPursuitTimeoutTicks.get();
    }
}
