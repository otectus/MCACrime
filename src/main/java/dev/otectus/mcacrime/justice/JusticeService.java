package dev.otectus.mcacrime.justice;

import dev.otectus.mcacrime.McaCrimeConfig;
import dev.otectus.mcacrime.api.model.CrimeCommunityKey;
import dev.otectus.mcacrime.enforcement.LegalTarget;
import dev.otectus.mcacrime.engine.CrimeState;
import dev.otectus.mcacrime.ledger.CrimeRecord;
import dev.otectus.mcacrime.memory.CrimeReport;
import dev.otectus.mcacrime.memory.ReportService;
import dev.otectus.mcacrime.state.world.CrimeWorldData;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;

import org.jetbrains.annotations.Nullable;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Shared evidence selection for challenge, review, settlement and arrest assessment. */
public final class JusticeService {
    public record Settings(boolean observations, boolean globalPropagation, double confidence) {
        public static Settings fromConfig() {
            var c = McaCrimeConfig.COMMON;
            return new Settings(c.enableObservations.get(), c.globalCrimePropagation.get(),
                    c.reportConfidenceThreshold.get());
        }
    }

    private JusticeService() {}

    public static LegalDecision forGuard(ServerLevel level, LivingEntity guard, ServerPlayer player) {
        return evaluate(CrimeWorldData.get(level.getServer()), player.getUUID(),
                ReportService.jurisdictionOf(level, guard), level.getGameTime(), Settings.fromConfig(),
                LegalTarget.isEscapedPrisoner(player), LegalTarget.isHoldingCaptive(player),
                CrimeState.isWanted(player), LegalTarget.isResistingArrest(player));
    }

    public static LegalDecision evaluate(MinecraftServer server, UUID offender,
                                         @Nullable CrimeCommunityKey jurisdiction, long now) {
        return evaluate(server == null ? null : CrimeWorldData.get(server), offender, jurisdiction,
                now, Settings.fromConfig(), false, false);
    }

    /** Reads the offender's indexed reports once, independent of their number of private cases. */
    public static LegalDecision evaluate(CrimeWorldData data, UUID offender,
                                         @Nullable CrimeCommunityKey jurisdiction, long now,
                                         Settings settings, boolean escaped, boolean holdingCaptive) {
        return evaluate(data, offender, jurisdiction, now, settings, escaped, holdingCaptive, false, false);
    }

    /** Wanted/refusal authorize interception independently of local knowledge of particular cases. */
    public static LegalDecision evaluate(CrimeWorldData data, UUID offender,
                                         @Nullable CrimeCommunityKey jurisdiction, long now,
                                         Settings settings, boolean escaped, boolean holdingCaptive,
                                         boolean wanted, boolean resistingArrest) {
        if (data == null || offender == null || data.isReadOnlyFutureData() || data.isLoadFailed())
            return new LegalDecision(offender, jurisdiction, List.of(), Set.of());
        Set<UUID> reported = new HashSet<>();
        for (CrimeReport report : data.reportsAgainst(offender)) {
            if (!report.expired(now) && ReportService.hasActionableCase(data, report)
                    && report.supportsArrest(settings.confidence())
                    && (settings.globalPropagation()
                        || jurisdiction != null && jurisdiction.equals(report.jurisdiction()))) {
                reported.add(report.incidentId());
            }
        }
        List<CrimeRecord> cases = new ArrayList<>();
        EnumSet<LegalDecision.Basis> basis = EnumSet.noneOf(LegalDecision.Basis.class);
        for (CrimeRecord record : data.actionableFor(offender)) {
            String detection = record.context().get("detection");
            LegalDecision.Basis reason = !settings.observations() ? LegalDecision.Basis.LEGACY_CASE
                    : "command".equals(detection) || "jailbreak".equals(detection)
                    ? LegalDecision.Basis.EXPLICIT_CASE
                    : reported.contains(record.id()) ? LegalDecision.Basis.REPORTED_CASE : null;
            if (reason != null) {
                cases.add(record);
                basis.add(reason);
            }
        }
        if (escaped) basis.add(LegalDecision.Basis.ESCAPED_PRISONER);
        if (holdingCaptive) basis.add(LegalDecision.Basis.HOLDING_CAPTIVE);
        if (wanted) basis.add(LegalDecision.Basis.WANTED);
        if (resistingArrest) basis.add(LegalDecision.Basis.RESISTING_ARREST);
        return new LegalDecision(offender, jurisdiction, cases, basis);
    }
}
