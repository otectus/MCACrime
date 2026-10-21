package dev.otectus.mcacrime.ai.thief;

import dev.otectus.mcacrime.McaCrimeConfig;

/**
 * A snapshot of the {@code criminalJobs.thief} config block.
 *
 * <p>Read once per decision rather than per candidate, and passed into the pure selectors so they can
 * be tested without a config spec at all. Every value here is server-authoritative common config.
 */
public record ThiefPolicy(int mugDurationTicks, int mugCooldownTicks, int scanIntervalTicks,
                          double targetSearchRadius, double guardAvoidRadius, double guardHardAbortRadius,
                          double guardRiskAbortThreshold) {

    public static ThiefPolicy resolve(net.minecraft.server.MinecraftServer server) {
        McaCrimeConfig.Common c = McaCrimeConfig.COMMON;
        return new ThiefPolicy(c.thiefMugDurationTicks.get(),
                dev.otectus.mcacrime.config.CrimeWorldSettings.resolve(server).thiefMugCooldownTicks(),
                c.thiefScanIntervalTicks.get(), c.thiefTargetSearchRadius.get(),
                c.thiefGuardAvoidRadius.get(), c.thiefGuardHardAbortRadius.get(),
                c.thiefGuardRiskAbortThreshold.get());
    }
}
