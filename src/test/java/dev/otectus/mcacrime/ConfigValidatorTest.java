package dev.otectus.mcacrime;

import dev.otectus.mcacrime.config.ConfigValidator;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pure config-validation checks (spec §12.3) — the part that needs no running game. */
class ConfigValidatorTest {

    @Test
    void validConfigHasNoProblems() {
        assertEquals(List.of(), ConfigValidator.validate(100, -100, 1.0, 360,
                List.of("mca:villager"), List.of("mca:guard")));
    }

    @Test
    void bandOrderingIsTheHeadlineCheck() {
        List<String> problems = ConfigValidator.validate(-200, -100, 1.0, 360, List.of(), List.of());
        assertTrue(problems.stream().anyMatch(p -> p.contains("Band thresholds invalid")), problems.toString());
    }

    @Test
    void negativeCaptivityReported() {
        List<String> problems = ConfigValidator.validate(100, -100, 1.0, -5, List.of(), List.of());
        assertTrue(problems.stream().anyMatch(p -> p.contains("maxCaptivityRealMinutes")), problems.toString());
    }

    @Test
    void outOfRangeUnwitnessedFactorReported() {
        List<String> problems = ConfigValidator.validate(100, -100, 1.5, 360, List.of(), List.of());
        assertTrue(problems.stream().anyMatch(p -> p.contains("unwitnessedKarmaFactor")), problems.toString());
    }

    @Test
    void garbageEntityIdReported() {
        List<String> problems = ConfigValidator.validate(100, -100, 1.0, 360,
                List.of("not a valid id!!"), List.of());
        assertTrue(problems.stream().anyMatch(p -> p.contains("protectedEntities") && p.contains("invalid")),
                problems.toString());
    }

    @Test
    void wildcardsAndTagsAndValidIdsAccepted() {
        // wildcard pattern, a tag reference, and a well-formed id all parse-pass
        assertEquals(List.of(), ConfigValidator.validate(100, -100, 1.0, 360,
                List.of("mca:*", "#minecraft:raiders", "minecraft:villager"), List.of()));
    }
}
