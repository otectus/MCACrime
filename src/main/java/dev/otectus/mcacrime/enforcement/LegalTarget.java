package dev.otectus.mcacrime.enforcement;

import dev.otectus.mcacrime.McaCrimeConfig;
import dev.otectus.mcacrime.captivity.CustodyRegistry;
import dev.otectus.mcacrime.crime.Band;
import dev.otectus.mcacrime.engine.CrimeState;
import dev.otectus.mcacrime.jail.JailState;
import dev.otectus.mcacrime.state.CrimeAttachments;
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

    /** Pure (back-compat): the §1.3 conditions without the resisting-arrest term. */
    public static boolean isLegalTarget(boolean wanted, Band band, boolean redIsLegalTarget,
                                        boolean escapedPrisoner, boolean holdingCaptive) {
        return isLegalTarget(wanted, band, redIsLegalTarget, escapedPrisoner, holdingCaptive, false);
    }

    /**
     * Pure: force against this entity is lawful when any condition holds (spec §1.3).
     *
     * <p>{@code resistingArrest} is the newest term and the one that makes a guard challenge mean
     * anything. Refusal used to be recorded in a transient map that was pruned for anybody who was not
     * <em>already</em> a Legal Target — so refusing when your Heat was below the Wanted threshold
     * unlocked force for a fraction of a second and then put you back where you started, and the guard
     * simply re-challenged. Refusing a lawful challenge is a standalone basis for force, exactly as the
     * message a refusing player is shown has always claimed.
     */
    public static boolean isLegalTarget(boolean wanted, Band band, boolean redIsLegalTarget,
                                        boolean escapedPrisoner, boolean holdingCaptive,
                                        boolean resistingArrest) {
        return wanted
                || (band == Band.RED && redIsLegalTarget)
                || escapedPrisoner
                || holdingCaptive
                || resistingArrest;
    }

    public static boolean isLegalTarget(ServerPlayer player) {
        return isLegalTarget(
                CrimeState.isWanted(player),
                CrimeState.getBand(player),
                McaCrimeConfig.COMMON.redIsLegalTarget.get(),
                isEscapedPrisoner(player),
                isHoldingCaptive(player),
                isResistingArrest(player));
    }

    /** True while the player's refusal of a guard challenge is still standing (spec §13.2). */
    public static boolean isResistingArrest(ServerPlayer player) {
        return CrimeAttachments.get(player).isResistingArrest();
    }

    /**
     * Whether <em>lethal</em> force against this player is lawful.
     *
     * <p>{@code allowKillingRed} shipped in 0.1.0 and was read by nothing, which made it indistinguishable
     * from {@code redIsLegalTarget} and left a server owner no way to say "you may subdue an outlaw but
     * not execute one". The distinction is real: a Wanted player or an active kidnapper may be killed
     * because of what they are doing right now, whereas a Red player is only carrying a reputation, and
     * whether a reputation is a death sentence is exactly the kind of thing a server should choose.
     */
    public static boolean isLethalForceLawful(ServerPlayer player) {
        if (!isLegalTarget(player)) {
            return false;
        }
        boolean redOnly = CrimeState.getBand(player) == Band.RED
                && !CrimeState.isWanted(player)
                && !isEscapedPrisoner(player)
                && !isHoldingCaptive(player)
                && !isResistingArrest(player);
        return !redOnly || McaCrimeConfig.COMMON.allowKillingRed.get();
    }

    public static boolean isEscapedPrisoner(ServerPlayer player) {
        JailState jail = CrimeAttachments.get(player).getJail();
        return jail != null && jail.isEscaped();
    }

    /** True when the player is an active kidnapper — holding an entity in unlawful custody (spec §1.3, §8). */
    public static boolean isHoldingCaptive(ServerPlayer player) {
        MinecraftServer server = player.getServer();
        return server != null && CustodyRegistry.isActiveKidnapper(server, player.getUUID());
    }

    /**
     * Pure: which of several simultaneously-true reasons is <em>the</em> reason (0.5.1).
     *
     * <p>Extracted from {@link #primaryReasonKey} rather than duplicated, because {@code OutlawResolver}
     * needs the same ordering and a second copy of it is a second thing to keep in step. Resisting comes
     * first because it is the most recent thing the player actually did: being told "you are Wanted"
     * after refusing a guard to their face explains the wrong half of the encounter.
     *
     * <p>{@code redBand} means "Red <em>and</em> redIsLegalTarget", not merely Red — carrying a
     * reputation is only a basis for force when the server has said it is.
     */
    public static LegalBasis basisOf(boolean wanted, boolean redBand, boolean escaped,
                                     boolean holdingCaptive, boolean resisting) {
        if (resisting) {
            return LegalBasis.RESISTING_ARREST;
        }
        if (holdingCaptive) {
            return LegalBasis.HOLDING_CAPTIVE;
        }
        if (wanted) {
            return LegalBasis.WANTED;
        }
        if (escaped) {
            return LegalBasis.ESCAPED_PRISONER;
        }
        if (redBand) {
            return LegalBasis.RED_BAND;
        }
        return LegalBasis.NONE;
    }

    /** The lang key explaining the primary reason a player is a Legal Target — for the §10.3 "why a guard attacks" message. */
    public static String primaryReasonKey(ServerPlayer player) {
        return basisOf(
                CrimeState.isWanted(player),
                CrimeState.getBand(player) == Band.RED && McaCrimeConfig.COMMON.redIsLegalTarget.get(),
                isEscapedPrisoner(player),
                isHoldingCaptive(player),
                isResistingArrest(player)).reasonKey();
    }
}
