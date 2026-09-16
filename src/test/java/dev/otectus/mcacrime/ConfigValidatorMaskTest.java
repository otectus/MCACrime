package dev.otectus.mcacrime;

import dev.otectus.mcacrime.config.ConfigValidator;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The three mask settings that parse cleanly and then cannot mean what the operator meant. */
class ConfigValidatorMaskTest {

    @Test
    void theShippedDefaultsAreQuiet() {
        assertEquals(List.of(), ConfigValidator.validateMask(true, true, true, 12.0, 1200, false));
    }

    @Test
    void disabledMasksReportNothingAtAll() {
        assertEquals(List.of(), ConfigValidator.validateMask(false, false, true, 0.0, 0, false));
    }

    @Test
    void deferringWithoutSuppressingIsReported() {
        List<String> problems = ConfigValidator.validateMask(true, false, true, 12.0, 1200, false);
        assertTrue(problems.stream().anyMatch(p -> p.contains("maskDefersHeat")), problems.toString());
    }

    @Test
    void aZeroUnmaskRadiusWhileDeferringIsReported() {
        List<String> problems = ConfigValidator.validateMask(true, true, true, 0.0, 1200, false);
        assertTrue(problems.stream().anyMatch(p -> p.contains("maskRemovalWitnessRadius")), problems.toString());
    }

    @Test
    void aMaskedCrimeWithNoConsequenceAtAllIsReported() {
        List<String> problems = ConfigValidator.validateMask(true, true, false, 12.0, 0, false);
        assertTrue(problems.stream().anyMatch(p -> p.contains("no consequence")), problems.toString());
    }
}
