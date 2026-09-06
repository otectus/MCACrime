package dev.otectus.mcacrime.economy.fence;

import dev.otectus.mcacrime.util.SafeMath;

/**
 * What a fence charges, and what it pays (spec §"Fence prices: karma versus Heat").
 *
 * <p>Pure, and deliberately so: the whole point of the section is that Karma and Heat pull in
 * opposite directions and must not be collapsed into one number, which is a claim only arithmetic
 * over plain values can be held to. Karma says whether the player is one of us and buys a discount;
 * Heat says how much law-enforcement attention the fence is taking on and charges for it; being
 * Wanted charges again on top. A notorious outlaw the guards are actively hunting therefore pays
 * <em>more</em> than a stranger, despite the discount, which is the outcome the spec asks for.
 *
 * <p>The one invariant that closes the arbitrage loop is {@code buy < sell} for the same item under
 * every combination of modifiers. It is enforced structurally rather than hoped for, and in 0.6.0 the
 * structure is a different one (audit finding B08). The buy price no longer derives from the marked-up
 * sale price: a markup that raised what the fence charges also raised what it paid, so a player who
 * bought an item while cool and sold it back while Wanted could clear the difference. Now the two
 * sides are computed from the same base independently — the sale price takes the discount and the
 * markups, the buy price takes {@code buyPriceRatio} and risk <em>only as a reduction</em> — so the
 * buy price can never rise with Heat, and {@code buyPriceRatio <= minimumPriceMultiplier} (clamped in
 * {@link FencePolicy}) keeps it below the lowest price the fence will ever sell at.
 *
 * <p>The two belt-and-braces bounds stay: the buy price is capped at one below the sale price, and
 * the sale price never falls below two, because a fence that sold for one could not buy for less and
 * still pay anything at all.
 */
public final class FencePricing {

    /** The lowest a fence ever sells for. See the class note on the {@code buy < sell} invariant. */
    private static final long MINIMUM_SELL_PRICE = 2L;

    /**
     * @param karma            the player's Karma, negative for criminal standing
     * @param heat             the player's Heat
     * @param wanted           whether the law is actively hunting them
     * @param redKarmaThreshold {@code bands.karmaRedThreshold} — the Karma at which affinity is total
     * @param heatCeiling      {@code bands.wantedHeatThreshold} — the Heat at which risk is total
     */
    public record PricingInputs(long karma, long heat, boolean wanted, long redKarmaThreshold, long heatCeiling) {
    }

    private FencePricing() {
    }

    /**
     * How far this player is toward being one of us, 0..1. Positive Karma is 0: a lawful player gets
     * no criminal discount at all, which is the spec's first matrix row.
     */
    public static double criminalAffinity(PricingInputs inputs) {
        if (inputs.karma() >= 0L) {
            return 0.0D;
        }
        double threshold = Math.abs((double) inputs.redKarmaThreshold());
        if (threshold <= 0.0D) {
            return 1.0D; // a pack that put the Red band at zero has made every criminal maximally trusted
        }
        return clamp01(Math.abs((double) inputs.karma()) / threshold);
    }

    /** How much attention doing business with this player attracts, 0..1. */
    public static double heatRisk(PricingInputs inputs) {
        if (inputs.heat() <= 0L) {
            return 0.0D;
        }
        double ceiling = (double) inputs.heatCeiling();
        if (ceiling <= 0.0D) {
            return 1.0D; // Wanted at zero Heat: any Heat at all is as hot as it gets
        }
        return clamp01((double) inputs.heat() / ceiling);
    }

    /** The spec's formula, clamped to {@code [minimumPriceMultiplier, maximumPriceMultiplier]}. */
    public static double sellMultiplier(PricingInputs inputs, FencePolicy policy) {
        double multiplier = 1.0D
                - policy.maxKarmaDiscount() * criminalAffinity(inputs)
                + policy.maxHeatMarkup() * heatRisk(inputs)
                + (inputs.wanted() ? policy.wantedMarkup() : 0.0D);
        double min = policy.minimumPriceMultiplier();
        double max = Math.max(min, policy.maximumPriceMultiplier());
        return Math.min(max, Math.max(min, multiplier));
    }

    /** What the player pays the fence for one of these. Never below {@link #MINIMUM_SELL_PRICE}. */
    public static long sellPrice(long base, PricingInputs inputs, FencePolicy policy) {
        long scaled = SafeMath.mulSat(Math.max(0L, base), sellMultiplier(inputs, policy));
        return Math.max(MINIMUM_SELL_PRICE, scaled);
    }

    /**
     * What the fence's risk does to what it is willing to pay, {@code (0, 1]}.
     *
     * <p>A reduction and never anything else. Taking hot goods off a hunted player is a risk the
     * fence prices into its offer by paying less for them — the same fact that raises the sale price
     * lowers this one, which is what stops the two moving together and opening the loop.
     */
    public static double buyRiskReduction(PricingInputs inputs, FencePolicy policy) {
        double risk = policy.maxHeatMarkup() * heatRisk(inputs)
                + (inputs.wanted() ? policy.wantedMarkup() : 0.0D);
        return clamp01(1.0D - Math.max(0.0D, SafeMath.finiteOr(risk, 0.0D)));
    }

    /**
     * What the fence pays the player for one of these — always at least 1, always strictly below
     * {@link #sellPrice}. Computed from the base price, not from the sale price: a player the fence
     * overcharges is also a player it underpays, but never by the same multiplier in both directions.
     */
    public static long buyPrice(long base, PricingInputs inputs, FencePolicy policy) {
        long sell = sellPrice(base, inputs, policy);
        double factor = policy.buyPriceRatio() * buyRiskReduction(inputs, policy);
        // Floor rather than round: paying a fraction more than the ratio says is the one direction
        // that could reach the sale price.
        double raw = Math.max(0L, base) * SafeMath.finiteOr(factor, 0.0D);
        long ratioed = raw >= (double) Long.MAX_VALUE ? Long.MAX_VALUE : (long) Math.floor(raw);
        return Math.max(1L, Math.min(ratioed, sell - 1L));
    }

    private static double clamp01(double value) {
        return value < 0.0D ? 0.0D : Math.min(1.0D, value);
    }
}
