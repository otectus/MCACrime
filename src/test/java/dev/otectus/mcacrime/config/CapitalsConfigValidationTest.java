package dev.otectus.mcacrime.config;

import dev.otectus.mcacrime.compat.CapitalsCapability;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The {@code [capitals]} section's validation (0.7.8), as the pure function it is. */
class CapitalsConfigValidationTest {

    private static List<String> validate(boolean installed, boolean bound, Set<CapitalsCapability> available,
                                         boolean chronicle, boolean herald, long sentence, long fine, boolean fines) {
        return ConfigValidator.validateCapitals(installed, bound, true, true, chronicle, herald, true,
                available::contains, sentence, 72000L, fine, fines);
    }

    @Test
    void theShippedDefaultsWithCapitalsBoundInFullAreClean() {
        assertEquals(List.of(), validate(true, true, EnumSet.allOf(CapitalsCapability.class), false, false,
                6000L, 32L, true));
    }

    @Test
    void nothingIsSaidAboutCapabilitiesWithoutCapitalsOrBeforeItBinds() {
        assertEquals(List.of(), validate(false, false, Set.of(), false, false, 6000L, 32L, true));
        assertEquals(List.of(), validate(true, false, Set.of(), false, false, 6000L, 32L, true),
                "a config read at mod setup, before any server exists, is not degraded");
    }

    @Test
    void decreesOnWithoutTheirCapabilitiesIsDegradedNotOff() {
        List<String> problems = validate(true, true, EnumSet.of(CapitalsCapability.REGISTRY,
                CapitalsCapability.AUTHORITY, CapitalsCapability.PLAYER_WARRANTS), false, false, 6000L, 32L, true);
        assertEquals(1, problems.size(), problems.toString());
        assertTrue(problems.get(0).contains("DEGRADED") && problems.get(0).contains("titles"), problems.get(0));
    }

    @Test
    void contradictorySwitchesAreReported() {
        List<String> problems = validate(true, true, EnumSet.allOf(CapitalsCapability.class), false, true,
                900_000L, 32L, false);
        assertEquals(3, problems.size(), problems.toString());
        assertTrue(problems.stream().anyMatch(p -> p.contains("chronicleHerald")));
        assertTrue(problems.stream().anyMatch(p -> p.contains("sentenceTicks")));
        assertTrue(problems.stream().anyMatch(p -> p.contains("enableFines")));
    }

    @Test
    void aChronicleThatCannotBeWrittenIsSaidSo() {
        List<String> problems = validate(true, true, EnumSet.complementOf(EnumSet.of(CapitalsCapability.CHRONICLE)),
                true, false, 6000L, 32L, true);
        assertEquals(1, problems.size(), problems.toString());
        assertTrue(problems.get(0).contains("chronicle"));
    }
}
