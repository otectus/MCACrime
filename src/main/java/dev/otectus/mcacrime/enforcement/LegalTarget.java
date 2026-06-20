package dev.otectus.mcacrime.enforcement;

import dev.otectus.mcacrime.McaCrimeConfig;
import dev.otectus.mcacrime.crime.Band;
import dev.otectus.mcacrime.engine.CrimeState;
import dev.otectus.mcacrime.jail.JailState;
import dev.otectus.mcacrime.state.CrimeCapabilities;
import net.minecraft.server.level.ServerPlayer;

/**
 * Resolves whether force against an entity is lawful right now (spec §1.3). The core is a pure truth
 * table (testable); the {@link ServerPlayer} adapter reads live Wanted/band/escape state. Future
 * predicates (attacking guards, resisting arrest, holding a captive) are left as parameters so Phase
 * 4/5 wire them without resorting the logic.
 *
 * <p>Drives guard pursuit, the client "why a guard is attacking" indicator, and the {@code CrimeGate}
 * lawful-force seam. The full Blue-attacks-innocent <em>penalty</em> (§4.3) is a Phase-5 seam, not here.
 */
public final class LegalTarget {

    private LegalTarget() {
    }

    /** Pure: force against this entity is lawful when any condition holds (spec §1.3). */
    public static boolean isLegalTarget(boolean wanted, Band band, boolean redIsLegalTarget, boolean escapedPrisoner) {
        return wanted
                || (band == Band.RED && redIsLegalTarget)
                || escapedPrisoner;
    }

    public static boolean isLegalTarget(ServerPlayer player) {
        return isLegalTarget(
                CrimeState.isWanted(player),
                CrimeState.getBand(player),
                McaCrimeConfig.COMMON.redIsLegalTarget.get(),
                isEscapedPrisoner(player));
    }

    public static boolean isEscapedPrisoner(ServerPlayer player) {
        return CrimeCapabilities.get(player)
                .map(data -> {
                    JailState jail = data.getJail();
                    return jail != null && jail.isEscaped();
                })
                .orElse(false);
    }
}
