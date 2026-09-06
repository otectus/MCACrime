package dev.otectus.mcacrime.economy.fence;

import dev.otectus.mcacrime.McaCrimeConfig;

/**
 * A snapshot of the {@code criminalJobs.fence} config block (0.5.1 §"Fence prices: karma versus Heat").
 *
 * <p>A record rather than a pile of {@code .get()} calls so the pricing maths is a pure function of
 * plain numbers and can be tested without a config file — and so one open trading screen prices every
 * offer against the same policy even if a reload lands mid-session.
 *
 * <p>The compact constructor holds the one inequality the arbitrage bound rests on (0.6.0, audit
 * finding B08): {@code buyPriceRatio} may never exceed {@code minimumPriceMultiplier}, because a
 * fence that pays a larger fraction of the base price than the smallest fraction it ever sells at can
 * be bought from and sold back to at a profit. Clamping here rather than at each call site means the
 * effective value is the same one every reader sees, and clamping a clamped value changes nothing —
 * {@code ConfigValidator} runs on every config reload and must stay idempotent.
 */
public record FencePolicy(double maxKarmaDiscount,
                          double maxHeatMarkup,
                          double wantedMarkup,
                          double minimumPriceMultiplier,
                          double maximumPriceMultiplier,
                          double buyPriceRatio,
                          long defaultBasePrice,
                          int offerCount,
                          int offerMaxUses,
                          int restockIntervalDays) {

    public FencePolicy {
        buyPriceRatio = Math.min(buyPriceRatio, minimumPriceMultiplier);
        offerMaxUses = Math.max(1, offerMaxUses);
    }

    /** The spec's defaults, for tests and for any caller reached before the config is loaded. */
    public static FencePolicy defaults() {
        return new FencePolicy(0.25D, 0.35D, 0.20D, 0.55D, 2.50D, 0.5D, 8L, 6, 8, 1);
    }

    public static FencePolicy fromConfig() {
        McaCrimeConfig.Common c = McaCrimeConfig.COMMON;
        try {
            return new FencePolicy(
                    c.fenceMaxKarmaDiscount.get(),
                    c.fenceMaxHeatMarkup.get(),
                    c.fenceWantedMarkup.get(),
                    c.fenceMinimumPriceMultiplier.get(),
                    c.fenceMaximumPriceMultiplier.get(),
                    c.fenceBuyPriceRatio.get(),
                    c.fenceDefaultBasePrice.get().longValue(),
                    c.fenceOfferCount.get(),
                    c.fenceOfferMaxUses.get(),
                    c.fenceRestockIntervalDays.get());
        } catch (IllegalStateException e) {
            return defaults(); // config not loaded yet (a datapack reload during startup)
        }
    }
}
