package dev.otectus.mcacrime.economy.fence;

import dev.otectus.mcacrime.McaCrimeConfig;

/**
 * A snapshot of the {@code criminalJobs.fence} config block (0.5.1 §"Fence prices: karma versus Heat").
 *
 * <p>A record rather than a pile of {@code .get()} calls so the pricing maths is a pure function of
 * plain numbers and can be tested without a config file — and so one open trading screen prices every
 * offer against the same policy even if a reload lands mid-session.
 */
public record FencePolicy(double maxKarmaDiscount,
                          double maxHeatMarkup,
                          double wantedMarkup,
                          double minimumPriceMultiplier,
                          double maximumPriceMultiplier,
                          double buyPriceRatio,
                          long defaultBasePrice,
                          int offerCount,
                          int restockIntervalDays) {

    /** The spec's defaults, for tests and for any caller reached before the config is loaded. */
    public static FencePolicy defaults() {
        return new FencePolicy(0.25D, 0.35D, 0.20D, 0.55D, 2.50D, 0.5D, 8L, 6, 1);
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
                    c.fenceRestockIntervalDays.get());
        } catch (IllegalStateException e) {
            return defaults(); // config not loaded yet (a datapack reload during startup)
        }
    }
}
