package dev.otectus.mcacrime;

import dev.otectus.mcacrime.config.ConfigValidator;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The 0.7.5 lock, lockpicking, prison and restraint settings, as reports rather than failures.
 *
 * <p>Every rule here is a setting that is individually legal and jointly surprising: a policy name
 * that falls back silently, a protection that is off, a window nobody can hit, a key that nothing
 * reads. None of them stops a server; all of them should be in the log before somebody spends an
 * evening wondering why their padlock did nothing.
 */
class ConfigValidatorLockTest {

    private static boolean mentions(List<String> problems, String fragment) {
        return problems.stream().anyMatch(problem -> problem.contains(fragment));
    }

    // --- locks ------------------------------------------------------------------------------------

    private static List<String> locks(String foreign, String automation, boolean breaking,
                                      boolean explosions, boolean pistons) {
        return ConfigValidator.validateLocks(16, foreign, automation, breaking, explosions, pistons, true);
    }

    @Test
    void theDefaultLockSettingsReportNothing() {
        assertEquals(List.of(), locks("REFUSE", "BLOCK_ALL", true, true, true));
    }

    @Test
    void anUnrecognisedPolicyNameIsNamedAlongWithWhatWillBeUsed() {
        assertTrue(mentions(locks("maybe", "BLOCK_ALL", true, true, true), "foreignLockPolicy"));
        assertTrue(mentions(locks("REFUSE", "sometimes", true, true, true), "automationPolicy"));
        assertTrue(mentions(locks("REFUSE", "sometimes", true, true, true), "BLOCK_ALL will be used"));
    }

    @Test
    void everyProtectionThatIsOffIsReported() {
        assertTrue(mentions(locks("REFUSE", "BLOCK_ALL", false, true, true), "pickaxe instead of a key"));
        assertTrue(mentions(locks("REFUSE", "BLOCK_ALL", true, false, true), "TNT is a lockpick"));
        assertTrue(mentions(locks("REFUSE", "BLOCK_ALL", true, true, false), "pushed away from the lock"));
        assertTrue(mentions(locks("REFUSE", "ALLOW_ALL", true, true, true),
                "a hopper empties a locked safe"));
    }

    @Test
    void aRingThatCanHoldNothingIsReportedAndNotTreatedAsErasure() {
        List<String> problems = ConfigValidator.validateLocks(0, "REFUSE", "BLOCK_ALL", true, true, true,
                true);
        assertTrue(mentions(problems, "maxKeysPerRing"));
        assertTrue(mentions(problems, "Existing rings keep their keys"));
    }

    // --- lockpicking -------------------------------------------------------------------------------

    private static List<String> picking(int divisor, int interval, double below, double above,
                                        double range, boolean destructive) {
        return ConfigValidator.validateLockpicking(true, divisor, interval, below, above, range,
                destructive);
    }

    @Test
    void theDefaultLockpickingSettingsReportNothing() {
        assertEquals(List.of(), picking(200, 2, 10.0D, 5.0D, 5.0D, false));
    }

    @Test
    void aDisabledMiniGameSaysSoAndStopsThere() {
        List<String> problems = ConfigValidator.validateLockpicking(false, 200, 2, 10.0D, 5.0D, 5.0D,
                false);
        assertEquals(1, problems.size(), "one message, not a list of consequences");
        assertTrue(mentions(problems, "a lockpick does nothing at all"));
    }

    @Test
    void aWindowThatCannotBeMissedOrCannotBeHitIsReported() {
        assertTrue(mentions(picking(200, 2, 170.0D, 170.0D, 5.0D, false), "covers half the dial"));
        assertTrue(mentions(picking(200, 2, 0.5D, 0.5D, 5.0D, false), "under a degree wide"));
    }

    @Test
    void anUnwinnableRateOrDrainIsReported() {
        assertTrue(mentions(picking(200, 40, 10.0D, 5.0D, 5.0D, false), "under one attempt a second"));
        assertTrue(mentions(picking(5, 2, 10.0D, 5.0D, 5.0D, false), "drains the meter so"));
        assertTrue(mentions(picking(200, 2, 10.0D, 5.0D, 30.0D, false), "beyond ordinary reach"));
    }

    @Test
    void theDestructiveParityOutcomeIsStatedPlainly() {
        List<String> problems = picking(200, 2, 10.0D, 5.0D, 5.0D, true);
        assertTrue(mentions(problems, "destroys it"));
        assertTrue(mentions(problems, "moved out first"), "and says the contents are not lost");
    }

    // --- prison ------------------------------------------------------------------------------------

    @Test
    void theSafeDefaultIsQuietAndAnOddSizeIsExplained() {
        assertEquals(List.of(), ConfigValidator.validatePrison(36));
        assertTrue(mentions(ConfigValidator.validatePrison(35), "rounded down to 27"));
        assertTrue(mentions(ConfigValidator.validatePrison(35), "Nothing already stored is lost"));
        assertTrue(mentions(ConfigValidator.validatePrison(18), "smaller than a chest"));
        assertTrue(mentions(ConfigValidator.validatePrison(18), "keeps the surplus"));
    }

    // --- the two restraint keys that had no rule -------------------------------------------------

    @Test
    void headTapeMufflingChatIsReportedAsTheModerationDecisionItIs() {
        assertFalse(mentions(ConfigValidator.validateRestraintDurability(40, 15, 5, 5, 5, 5, false),
                "headTapeMufflesTextChat"), "off is the default and says nothing");
        List<String> problems = ConfigValidator.validateRestraintDurability(40, 15, 5, 5, 5, 5, true);
        assertTrue(mentions(problems, "headTapeMufflesTextChat"));
        assertTrue(mentions(problems, "cannot ask to be"));
    }

    @Test
    void lowHealthFractionIsCheckedAgainstTheGateThatReadsIt() {
        // Nothing lists low_health: the default is quiet, a changed value is reported as unread.
        assertFalse(mentions(ConfigValidator.validateRestraintApplication(60, 4.0D, true, true, 0.35D,
                List.of()), "lowHealthFraction"));
        assertTrue(mentions(ConfigValidator.validateRestraintApplication(60, 4.0D, true, true, 0.8D,
                List.of()), "nothing reads it"));

        // With the gate listed, the two useless extremes are named.
        assertTrue(mentions(ConfigValidator.validateRestraintApplication(60, 4.0D, true, true, 0.0D,
                List.of("low_health")), "no living subject ever"));
        assertTrue(mentions(ConfigValidator.validateRestraintApplication(60, 4.0D, true, true, 1.0D,
                List.of("low_health")), "refuses nothing"));
        assertFalse(mentions(ConfigValidator.validateRestraintApplication(60, 4.0D, true, true, 0.35D,
                List.of("low_health")), "lowHealthFraction"));
    }
}
