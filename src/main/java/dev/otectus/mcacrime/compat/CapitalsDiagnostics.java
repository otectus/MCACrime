package dev.otectus.mcacrime.compat;

import dev.otectus.mcacrime.McaCrimeConfig;
import dev.otectus.mcacrime.api.model.CrimeCommunityKey;
import dev.otectus.mcacrime.enforcement.Jurisdictions;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;

/**
 * Plain-text lines describing the MCA Capitals seam (0.7.8), for {@code /crime debug capitals}.
 *
 * <p>No UUIDs of players beyond the ruler of the village the operator is standing in, so the output can
 * be pasted into a bug report.
 */
public final class CapitalsDiagnostics {

    private static final int MAX_UNRESOLVED_LINES = 6;

    private CapitalsDiagnostics() {
    }

    /** Installation, binding and capability state. */
    public static List<String> summary() {
        List<String> lines = new ArrayList<>();
        boolean installed = CapitalsBridge.installed();
        lines.add("MCA Capitals: installed=" + installed
                + (installed ? " version=" + blankAsUnknown(CapitalsBridge.detectedVersion()) : "")
                + " enabled=" + CapitalsBridge.integrationEnabled()
                + " state=" + CapitalsBridge.status());
        if (!installed) {
            lines.add("  not installed: royal decrees are unavailable and nothing else changes.");
            return lines;
        }
        for (CapitalsCapability capability : CapitalsCapability.values()) {
            lines.add("  " + capability.id() + ": " + (CapitalsBridge.has(capability) ? "bound" : "unavailable")
                    + " - " + capability.description());
        }
        lines.add("  royal decrees: " + decreeState());
        List<String> unresolved = CapitalsBridge.unresolvedMembers();
        if (!unresolved.isEmpty()) {
            lines.add("  unbound member(s): " + unresolved.size());
            unresolved.stream().limit(MAX_UNRESOLVED_LINES).forEach(member -> lines.add("    " + member));
        }
        return lines;
    }

    /** Why decrees are or are not available, in one phrase. */
    public static String decreeState() {
        if (!CapitalsBridge.installed()) {
            return "unavailable (MCA Capitals is not installed)";
        }
        if (!CapitalsBridge.integrationEnabled()) {
            return "off ([capitals] enabled is false)";
        }
        if (!decreesSwitch()) {
            return "off ([capitals.decrees] enabled is false)";
        }
        if (!CapitalsBridge.decreesReady()) {
            List<String> missing = new ArrayList<>();
            CapitalsBridge.missingForDecrees().forEach(capability -> missing.add(capability.id()));
            return "degraded (missing capability: " + String.join(", ", missing) + ")";
        }
        return "ready";
    }

    private static boolean decreesSwitch() {
        try {
            return McaCrimeConfig.COMMON.decreesEnabled.get();
        } catch (Throwable t) {
            return false;
        }
    }

    /** The capital, if any, seated in the village at {@code pos}. */
    public static List<String> here(@Nullable ServerLevel level, @Nullable BlockPos pos) {
        List<String> lines = new ArrayList<>();
        if (level == null || pos == null) {
            return lines;
        }
        OptionalInt village = McaCompat.findNearestVillageId(level, pos, communityRadius());
        if (village.isEmpty()) {
            lines.add("  here: no MCA village");
            return lines;
        }
        CrimeCommunityKey community = CrimeCommunityKey.of(level.dimension(), village.getAsInt()).orElse(null);
        String label = Jurisdictions.label(level, community).getString();
        CapitalsQueryResult<CapitalView> capital = CapitalsBridge.capitalAt(level, village.getAsInt());
        if (capital instanceof CapitalsQueryResult.Available<CapitalView> available) {
            CapitalView view = available.value();
            String ruler = view.playerSovereignId() == null ? "no player on the throne"
                    : "player " + playerName(level, view) + " rules";
            lines.add("  here: " + label + " is a capital (state " + view.state() + "), " + ruler);
        } else {
            lines.add("  here: " + label + " - capital " + capital.describe());
        }
        return lines;
    }

    private static String playerName(ServerLevel level, CapitalView view) {
        var player = level.getServer().getPlayerList().getPlayer(view.playerSovereignId());
        return player == null ? "(offline)" : player.getGameProfile().getName();
    }

    private static int communityRadius() {
        try {
            return McaCrimeConfig.COMMON.communitySearchRadius.get();
        } catch (Throwable t) {
            return 0;
        }
    }

    private static String blankAsUnknown(String value) {
        return value == null || value.isBlank() ? "unknown" : value;
    }
}
