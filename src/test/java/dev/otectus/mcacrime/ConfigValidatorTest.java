package dev.otectus.mcacrime;

import dev.otectus.mcacrime.config.ConfigValidator;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

    // ------------------------------------------------------------------ integrations

    private static List<String> integrations(int interval, int budget, int attempts, int base,
                                             int max, int retention, String fine, String served) {
        return ConfigValidator.validateIntegrations(interval, budget, attempts, base, max, retention,
                fine, served);
    }

    @Test
    void theDefaultIntegrationConfigIsValid() {
        assertTrue(integrations(100, 8, 6, 200, 24000, 168000, "atoned", "atoned").isEmpty());
    }

    /** A queue that is never drained is worse than no queue: work accrues and nothing says why. */
    @Test
    void aPumpThatNeverRunsIsRejected() {
        assertFalse(integrations(0, 8, 6, 200, 24000, 168000, "atoned", "atoned").isEmpty());
        assertFalse(integrations(100, 0, 6, 200, 24000, 168000, "atoned", "atoned").isEmpty());
    }

    @Test
    void aBackoffCeilingBelowItsOwnFloorIsRejected() {
        assertFalse(integrations(100, 8, 6, 24000, 200, 168000, "atoned", "atoned").isEmpty());
    }

    /** Without a replay window, a retried transaction is applied a second time. */
    @Test
    void aZeroDedupeWindowIsRejected() {
        assertFalse(integrations(100, 8, 6, 200, 24000, 0, "atoned", "atoned").isEmpty());
    }

    /** A fine must not be configurable into a pardon, so only the two amends statuses are accepted. */
    @Test
    void onlyTheAmendsStatusesAreAcceptedForSettledCases() {
        assertTrue(integrations(100, 8, 6, 200, 24000, 168000, "apologized", "apologized").isEmpty());
        assertFalse(integrations(100, 8, 6, 200, 24000, 168000, "forgiven", "atoned").isEmpty());
        assertFalse(integrations(100, 8, 6, 200, 24000, 168000, "atoned", "disproven").isEmpty());
    }

    // --- weapon classification lists (0.5.0) ---

    private static List<String> weapons(List<String> whitelist, List<String> blacklist) {
        return ConfigValidator.validateWeapons(whitelist, blacklist,
                List.of("gun", "rifle"), List.of("tacz"), 3.0);
    }

    @Test
    void theDefaultWeaponListsAreValid() {
        assertTrue(weapons(List.of(), List.of()).isEmpty());
        assertTrue(weapons(List.of("minecraft:stick", "#c:tools/spear"), List.of("minecraft:trident")).isEmpty());
    }

    @Test
    void aMalformedWeaponIdIsReportedAsAnItem() {
        List<String> problems = weapons(List.of("NOT AN ID"), List.of());
        assertTrue(problems.stream().anyMatch(p -> p.contains("invalid item id")), problems.toString());
    }

    /** Nothing expands a wildcard for item lists, so accepting one would promise a match that never comes. */
    @Test
    void wildcardsAreRejectedInWeaponLists() {
        List<String> problems = weapons(List.of("minecraft:*"), List.of());
        assertTrue(problems.stream().anyMatch(p -> p.contains("wildcard")), problems.toString());
    }

    @Test
    void anItemOnBothWeaponListsIsReportedAsRedundant() {
        List<String> problems = weapons(List.of("minecraft:stick"), List.of("minecraft:stick"));
        assertTrue(problems.stream().anyMatch(p -> p.contains("blacklist wins")), problems.toString());
    }

    /** A blank keyword is a substring of every path, so it would arm the entire item registry. */
    @Test
    void aBlankGunKeywordIsRejected() {
        List<String> problems = ConfigValidator.validateWeapons(List.of(), List.of(),
                List.of("gun", "  "), List.of("tacz"), 3.0);
        assertTrue(problems.stream().anyMatch(p -> p.contains("gunKeywords")), problems.toString());
    }

    @Test
    void aBlankOrUnparseableWeaponModNamespaceIsRejected() {
        assertFalse(ConfigValidator.validateWeapons(List.of(), List.of(),
                List.of("gun"), List.of(""), 3.0).isEmpty());
        assertFalse(ConfigValidator.validateWeapons(List.of(), List.of(),
                List.of("gun"), List.of("Not A Namespace"), 3.0).isEmpty());
    }

    @Test
    void aNegativeAttackDamageThresholdIsRejected() {
        List<String> problems = ConfigValidator.validateWeapons(List.of(), List.of(),
                List.of("gun"), List.of("tacz"), -1.0);
        assertTrue(problems.stream().anyMatch(p -> p.contains("autoDetectMinAttackDamage")), problems.toString());
    }
}
