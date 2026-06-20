package dev.otectus.mcacrime.enforcement;

import dev.otectus.mcacrime.McaCrimeConfig;
import dev.otectus.mcacrime.captivity.CustodyRegistry;
import dev.otectus.mcacrime.crime.Band;
import dev.otectus.mcacrime.engine.CrimeState;
import dev.otectus.mcacrime.jail.JailState;
import dev.otectus.mcacrime.state.CrimeCapabilities;
import dev.otectus.mcacrime.state.PlayerCrimeData;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.UUID;

/**
 * Resolves whether force against an entity is lawful right now (spec §1.3). The core is a pure truth
 * table (testable); the {@link ServerPlayer} adapter reads live Wanted/band/escape/captor state. Remaining
 * predicates (attacking guards, resisting arrest) are left as parameters so later phases wire them without
 * resorting the logic.
 *
 * <p>Drives guard pursuit, the client "why a guard is attacking" indicator, and the {@code CrimeGate}
 * lawful-force seam. The full Blue-attacks-innocent <em>penalty</em> (§4.3) is a Phase-5 seam, not here.
 */
public final class LegalTarget {

    private LegalTarget() {
    }

    /** Pure (back-compat): the §1.3 conditions without the active-kidnapper term. */
    public static boolean isLegalTarget(boolean wanted, Band band, boolean redIsLegalTarget, boolean escapedPrisoner) {
        return isLegalTarget(wanted, band, redIsLegalTarget, escapedPrisoner, false);
    }

    /** Pure: force against this entity is lawful when any condition holds (spec §1.3). */
    public static boolean isLegalTarget(boolean wanted, Band band, boolean redIsLegalTarget,
                                        boolean escapedPrisoner, boolean holdingCaptive) {
        return wanted
                || (band == Band.RED && redIsLegalTarget)
                || escapedPrisoner
                || holdingCaptive;
    }

    public static boolean isLegalTarget(ServerPlayer player) {
        return isLegalTarget(
                CrimeState.isWanted(player),
                CrimeState.getBand(player),
                McaCrimeConfig.COMMON.redIsLegalTarget.get(),
                isEscapedPrisoner(player),
                isHoldingCaptive(player));
    }

    public static boolean isEscapedPrisoner(ServerPlayer player) {
        return CrimeCapabilities.get(player)
                .map(data -> {
                    JailState jail = data.getJail();
                    return jail != null && jail.isEscaped();
                })
                .orElse(false);
    }

    /** True when the player is an active kidnapper — holding an entity in unlawful custody (spec §1.3, §8). */
    public static boolean isHoldingCaptive(ServerPlayer player) {
        UUID held = CrimeCapabilities.get(player).map(PlayerCrimeData::getHeldCaptiveRef).orElse(null);
        MinecraftServer server = player.getServer();
        if (held == null || server == null) {
            return false;
        }
        return CustodyRegistry.get(server, held)
                .filter(r -> !r.isLawful() && r.getOwner().isKidnapper(player.getUUID()))
                .isPresent();
    }

    /** The lang key explaining the primary reason a player is a Legal Target — for the §10.3 "why a guard attacks" message. */
    public static String primaryReasonKey(ServerPlayer player) {
        if (isHoldingCaptive(player)) {
            return "mcacrime.msg.guardaggro.captor";
        }
        if (CrimeState.isWanted(player)) {
            return "mcacrime.msg.guardaggro.wanted";
        }
        if (isEscapedPrisoner(player)) {
            return "mcacrime.msg.guardaggro.escaped";
        }
        if (CrimeState.getBand(player) == Band.RED && McaCrimeConfig.COMMON.redIsLegalTarget.get()) {
            return "mcacrime.msg.guardaggro.red";
        }
        return "mcacrime.msg.guardaggro.generic";
    }
}
