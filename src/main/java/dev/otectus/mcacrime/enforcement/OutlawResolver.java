package dev.otectus.mcacrime.enforcement;

import dev.otectus.mcacrime.McaCrimeConfig;
import dev.otectus.mcacrime.crime.Band;
import dev.otectus.mcacrime.engine.CrimeState;
import dev.otectus.mcacrime.ledger.Warrant;
import dev.otectus.mcacrime.state.world.CrimeWorldData;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import javax.annotation.Nullable;

/**
 * The single legal authority (0.5.1): one call answers may-they-be-attacked, may-they-be-killed, and
 * is-there-a-price-on-them, from one set of facts.
 *
 * <p>Those three questions were asked in four places by three different pieces of code, and the
 * failure mode is specific and unpleasant: a hunter uses force the law authorises, and a subsystem
 * that consulted a different predicate charges them with assault for it. Every caller now reads a
 * field off one {@link OutlawStatus}.
 *
 * <p>This class does not restate {@link LegalTarget}. It <em>delegates</em> the combat-target decision
 * to {@code LegalTarget.isLegalTarget}'s pure overload and reproduces lethal force from the same terms
 * {@code LegalTarget.isLethalForceLawful} uses. That is deliberate: a resolver that reimplemented the
 * truth table would be a fourth copy of it, and the whole point is that there is one.
 */
public final class OutlawResolver {

    private OutlawResolver() {
    }

    /** Reads the live player's Wanted/band/escape/captor/resisting state and their open warrant, if any. */
    public static OutlawStatus resolve(ServerPlayer target) {
        if (target == null) {
            return OutlawStatus.none(0L, 0L, Band.GREY);
        }
        MinecraftServer server = target.getServer();
        Warrant warrant = server == null ? null : CrimeWorldData.get(server).warrant(target.getUUID());
        return evaluate(
                CrimeState.isWanted(target),
                CrimeState.getBand(target),
                McaCrimeConfig.COMMON.redIsLegalTarget.get(),
                McaCrimeConfig.COMMON.allowKillingRed.get(),
                LegalTarget.isEscapedPrisoner(target),
                LegalTarget.isHoldingCaptive(target),
                LegalTarget.isResistingArrest(target),
                CrimeState.getHeat(target),
                CrimeState.getKarma(target),
                warrant,
                McaCrimeConfig.COMMON.bountyEnabled.get(),
                McaCrimeConfig.COMMON.redBandBountyEligible.get());
    }

    /**
     * Pure: the whole legal picture from facts already gathered.
     *
     * <p>{@code bountyEligible} needs an <em>open</em> warrant on top of a qualifying basis. A closed
     * warrant is kept rather than deleted so an old claim key still resolves, and paying against one
     * would be paying twice for the same wanted state.
     */
    public static OutlawStatus evaluate(boolean wanted, Band band, boolean redIsLegalTarget,
                                        boolean allowKillingRed, boolean escapedPrisoner,
                                        boolean holdingCaptive, boolean resistingArrest,
                                        long heat, long karma, @Nullable Warrant warrant,
                                        boolean bountyEnabled, boolean redBandBountyEligible) {
        boolean lawfulCombatTarget = LegalTarget.isLegalTarget(
                wanted, band, redIsLegalTarget, escapedPrisoner, holdingCaptive, resistingArrest);

        // The same "Red and nothing else" term isLethalForceLawful applies: a Wanted player or an
        // active kidnapper may be killed for what they are doing, whereas a Red player is only
        // carrying a reputation, and whether a reputation is a death sentence is a server's choice.
        boolean redOnly = band == Band.RED && !wanted && !escapedPrisoner && !holdingCaptive && !resistingArrest;
        boolean lethalForceLawful = lawfulCombatTarget && (!redOnly || allowKillingRed);

        LegalBasis basis = LegalTarget.basisOf(
                wanted, band == Band.RED && redIsLegalTarget, escapedPrisoner, holdingCaptive, resistingArrest);

        boolean qualifyingBasis = basis == LegalBasis.WANTED
                || (redBandBountyEligible && basis == LegalBasis.RED_BAND);
        boolean warrantOpen = warrant != null && warrant.open();
        boolean bountyEligible = bountyEnabled && warrantOpen && qualifyingBasis;

        return new OutlawStatus(lawfulCombatTarget, lethalForceLawful, bountyEligible, basis, heat, karma,
                band, warrant == null ? null : warrant.id(), warrant == null ? 0L : warrant.revision());
    }
}
