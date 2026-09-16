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
        return isLegalTarget(wanted, band, redIsLegalTarget, escapedPrisoner, holdingCaptive,
                resistingArrest, false);
    }

    /**
     * Pure: the same table with the masked-pursuit term (0.7.0).
     *
     * <p>A mask suppresses the Heat a crime would have generated, which is exactly why it needs a term
     * of its own here: without one, a responder who watched a masked figure rob somebody in front of
     * them would have no lawful reason to do anything about it. What the responder saw was a crime,
     * not a name, and this is the only consequence that survives not knowing the name.
     */
    public static boolean isLegalTarget(boolean wanted, Band band, boolean redIsLegalTarget,
                                        boolean escapedPrisoner, boolean holdingCaptive,
                                        boolean resistingArrest, boolean maskedPursuit) {
        return wanted
                || (band == Band.RED && redIsLegalTarget)
                || escapedPrisoner
                || holdingCaptive
                || resistingArrest
                || maskedPursuit;
    }

    public static boolean isLegalTarget(ServerPlayer player) {
        return isLegalTarget(
                CrimeState.isWanted(player),
                CrimeState.getBand(player),
                redTargetingAllowed(player),
                isEscapedPrisoner(player),
                isHoldingCaptive(player),
                isResistingArrest(player),
                isMaskedPursuit(player));
    }

    /** True while a responder who saw a masked crime is still hunting the figure they saw (0.7.0). */
    public static boolean isMaskedPursuit(ServerPlayer player) {
        return CrimeAttachments.get(player).isMaskedPursuit();
    }

    /**
     * Whether the Red-band term applies to this player right now.
     *
     * <p>Ordinarily it is just {@code redIsLegalTarget}. The exception is {@code
     * maskSuppressesRedBandTargeting}, which closes the hole an operator can otherwise fall into:
     * masked crimes still cost Karma, so a masked murderer turns Red and becomes a lawful target for
     * the reputation the mask was supposed to be hiding. The Wanted term is never suppressed this way
     * — being Wanted is a live pursuit, not a reputation.
     */
    public static boolean redTargetingAllowed(ServerPlayer player) {
        var c = McaCrimeConfig.COMMON;
        return c.redIsLegalTarget.get()
                && !(c.maskEnabled.get() && c.maskSuppressesRedBandTargeting.get()
                     && dev.otectus.mcacrime.mask.Masks.isMasked(player));
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
        // The masked term gets the same treatment for the same reason: a responder chasing somebody
        // they cannot name is chasing a suspicion, and whether a suspicion is a death sentence is a
        // server's choice. When both soft bases hold, both switches have to be on.
        boolean maskedOnly = isMaskedPursuit(player)
                && !CrimeState.isWanted(player)
                && !isEscapedPrisoner(player)
                && !isHoldingCaptive(player)
                && !isResistingArrest(player);
        return (!redOnly || McaCrimeConfig.COMMON.allowKillingRed.get())
                && (!maskedOnly || McaCrimeConfig.COMMON.maskedOffenderLethalForce.get());
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
        return basisOf(wanted, redBand, escaped, holdingCaptive, resisting, false);
    }

    /** The same ordering with the masked-pursuit basis, which sits below a captor and above Wanted. */
    public static LegalBasis basisOf(boolean wanted, boolean redBand, boolean escaped,
                                     boolean holdingCaptive, boolean resisting, boolean maskedPursuit) {
        if (resisting) {
            return LegalBasis.RESISTING_ARREST;
        }
        if (holdingCaptive) {
            return LegalBasis.HOLDING_CAPTIVE;
        }
        if (maskedPursuit) {
            return LegalBasis.MASKED_OFFENDER;
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
                CrimeState.getBand(player) == Band.RED && redTargetingAllowed(player),
                isEscapedPrisoner(player),
                isHoldingCaptive(player),
                isResistingArrest(player),
                isMaskedPursuit(player)).reasonKey();
    }
}
