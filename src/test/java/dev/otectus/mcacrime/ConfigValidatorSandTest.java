package dev.otectus.mcacrime;

import dev.otectus.mcacrime.config.ConfigValidator;
import dev.otectus.mcacrime.effect.SandExposurePolicy;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The Sand Bottle settings that parse and still cannot mean what they say (0.7.2 §13.3). */
class ConfigValidatorSandTest {

    private static List<String> validate(int cooldown, int direct, int splash, double radius, int recovery) {
        return ConfigValidator.validateSandBottle(true, cooldown, direct, splash, radius, recovery, false);
    }

    private static boolean mentions(List<String> problems, String fragment) {
        return problems.stream().anyMatch(p -> p.contains(fragment));
    }

    @Test
    void theShippedDefaultsAreValid() {
        assertTrue(validate(SandExposurePolicy.DEFAULT_COOLDOWN_TICKS,
                SandExposurePolicy.DEFAULT_DIRECT_DURATION_TICKS,
                SandExposurePolicy.DEFAULT_SPLASH_DURATION_TICKS,
                SandExposurePolicy.DEFAULT_RADIUS,
                SandExposurePolicy.DEFAULT_RECOVERY_TICKS).isEmpty());
    }

    @Test
    void aDisabledFeatureIsNeverReportedAsMisconfigured() {
        assertTrue(ConfigValidator.validateSandBottle(false, 0, 1, 999, 0.0D, 0, true).isEmpty());
    }

    @Test
    void aSplashLongerThanADirectHitIsReported() {
        assertTrue(mentions(validate(80, 40, 80, 2.0D, 60), "sandSplashDurationTicks"));
        assertFalse(mentions(validate(80, 80, 40, 2.0D, 60), "sandSplashDurationTicks"));
    }

    @Test
    void durationsBelowTheMinimumApplicationAreReportedBecauseTheyNeverApply() {
        assertTrue(mentions(validate(80, 5, 0, 2.0D, 60), "sandDirectDurationTicks"));
        assertTrue(mentions(validate(80, 80, 5, 2.0D, 60), "no splash victim"));
    }

    @Test
    void aZeroRadiusAndAZeroCooldownAreBothReported() {
        assertTrue(mentions(validate(80, 80, 40, 0.0D, 60), "sandRadius"));
        assertTrue(mentions(validate(0, 80, 40, 2.0D, 60), "sandCooldownTicks"));
    }

    @Test
    void aNonPositiveRecoveryWindowIsReportedAsTheStunLockItIs() {
        assertTrue(mentions(validate(80, 80, 40, 2.0D, 0), "alternate bottles"));
    }

    @Test
    void playerEffectsWithAnUnsafeRecoveryWindowAreCalledOutSeparately() {
        assertTrue(mentions(ConfigValidator.validateSandBottle(true, 80, 80, 40, 2.0D, 5, true),
                "stun-lock"));
        assertFalse(mentions(ConfigValidator.validateSandBottle(true, 80, 80, 40, 2.0D, 5, false),
                "stun-lock"));
    }
}
