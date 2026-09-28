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

    // ------------------------------------------------------------------ currency

    @Test
    void theDefaultCurrencyPairIsClean() {
        assertEquals(List.of(), ConfigValidator.validateCurrency("mcacrime:emerald", "minecraft:emerald"));
        assertEquals(List.of(), ConfigValidator.validateCurrency("mcacrime:item", "minecraft:gold_nugget"));
    }

    @Test
    void blankCurrencyItemReported() {
        List<String> problems = ConfigValidator.validateCurrency("mcacrime:item", "  ");
        assertTrue(problems.stream().anyMatch(p -> p.contains("currencyItem") && p.contains("blank")),
                problems.toString());
    }

    @Test
    void unparseableCurrencyItemReported() {
        List<String> problems = ConfigValidator.validateCurrency("mcacrime:item", "not an item!!");
        assertTrue(problems.stream().anyMatch(p -> p.contains("currencyItem") && p.contains("not a valid id")),
                problems.toString());
    }

    @Test
    void airAsTheItemCurrencyReported() {
        // Parses fine and means "pay in nothing", which would make every fine free.
        List<String> problems = ConfigValidator.validateCurrency("mcacrime:item", "minecraft:air");
        assertTrue(problems.stream().anyMatch(p -> p.contains("currencyItem") && p.contains("air")),
                problems.toString());
    }

    @Test
    void airIsToleratedWhenNothingIsPayingInItems() {
        assertEquals(List.of(), ConfigValidator.validateCurrency("mcacrime:emerald", "minecraft:air"));
    }

    @Test
    void blankCurrencyIdReported() {
        List<String> problems = ConfigValidator.validateCurrency("", "minecraft:emerald");
        assertTrue(problems.stream().anyMatch(p -> p.contains("currencyId") && p.contains("blank")),
                problems.toString());
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

    // --- the 0.7.5 rule blocks, one per group (M7.1, M7.7) ---

    /**
     * The ordering rule that turns every escort into an execution when it is broken.
     *
     * <p>A tether that starts hurting a subject before it starts pulling them has no "held but
     * unharmed" band at all. The pair ships the right way round and nothing used to check it.
     */
    @Test
    void aTetherThatHurtsBeforeItPullsIsReported() {
        assertTrue(ConfigValidator.validateTransport(5.0, 12.0, 2.0, true, 8).isEmpty(),
                "the shipped transport numbers are clean");
        List<String> inverted = ConfigValidator.validateTransport(12.0, 5.0, 2.0, true, 8);
        assertTrue(inverted.stream().anyMatch(p -> p.contains("first tick of tension")), inverted.toString());
        List<String> equal = ConfigValidator.validateTransport(8.0, 8.0, 2.0, true, 8);
        assertFalse(equal.isEmpty(), "equal lengths leave no band either");
    }

    @Test
    void aTetherThatCanKillALawfulPrisonerIsReported() {
        assertFalse(ConfigValidator.validateTransport(5.0, 12.0, 2.0, false, 8).isEmpty());
        assertTrue(ConfigValidator.validateTransport(5.0, 12.0, 12.0, true, 8).stream()
                .anyMatch(p -> p.contains("under a second")));
    }

    /** Session caps: a cap of one is a server where only one player may act at a time. */
    @Test
    void theSessionCapsAreReportedWhenTheyDefeatThemselves() {
        assertTrue(ConfigValidator.validateRestraints(64, 200).isEmpty());
        assertTrue(ConfigValidator.validateRestraints(1, 200).stream()
                .anyMatch(p -> p.contains("only one player at a time")));
        assertTrue(ConfigValidator.validateRestraints(64, 10).stream()
                .anyMatch(p -> p.contains("under one second")));
    }

    /** The frisking rules: the shipped numbers are clean, and the two switches say what they cost. */
    @Test
    void friskingSwitchesAreReportedWhenTurnedOff() {
        assertTrue(ConfigValidator.validateFrisking(5.0, 1200, 5, true, true).isEmpty(),
                "the shipped frisking numbers are clean");
        assertTrue(ConfigValidator.validateFrisking(5.0, 1200, 5, false, true).stream()
                .anyMatch(p -> p.contains("requiresArmRestraint")));
        assertTrue(ConfigValidator.validateFrisking(5.0, 1200, 5, true, false).stream()
                .anyMatch(p -> p.contains("lawfulSeizureToEscrow")));
    }

    /** Safe slot bounds: a safe has to hold something and has to fit a screen. */
    @Test
    void aSafeWithNoUsableSlotCountIsReported() {
        assertTrue(ConfigValidator.validatePrison(36).isEmpty());
        assertFalse(ConfigValidator.validatePrison(37).isEmpty(), "a part row cannot be drawn");
    }

    /** Positive durability: gear that one input ends is gear that does nothing. */
    @Test
    void flimsyRestraintDurabilityIsReported() {
        assertTrue(ConfigValidator.validateRestraintDurability(40, 15, 5, 5, 5, 5, false).isEmpty());
        assertTrue(ConfigValidator.validateRestraintDurability(40, 1, 5, 5, 5, 5, false).stream()
                .anyMatch(p -> p.contains("single")));
        assertTrue(ConfigValidator.validateRestraintDurability(10, 15, 5, 5, 5, 5, false).stream()
                .anyMatch(p -> p.contains("lighter restraint is the harder")));
    }

    // --- §3.19 capital sentencing ---

    /**
     * The one refused combination: a death sentence that needs no device.
     *
     * <p>Everything else in the group is a warning. This one is the user's own boundary - execution is
     * always a deliberate act at a device - and turning the requirement off while the feature is on is
     * the only way to ask for a death with nothing deliberate behind it.
     */
    @Test
    void aCapitalFeatureWithNoDeviceRequirementIsRefused() {
        assertTrue(ConfigValidator.validateCapitalPunishment(true, false, 1200, true).stream()
                .anyMatch(p -> p.contains("set enabled = false instead")));
        assertTrue(ConfigValidator.validateCapitalPunishment(false, false, 1200, true).stream()
                .noneMatch(p -> p.contains("requiresExecutionDevice")),
                "with the feature off the combination is simply unused");
    }

    @Test
    void theShippedCapitalGroupIsClean() {
        assertEquals(List.of(), ConfigValidator.validateCapitalPunishment(true, true, 1200, true,
                true, false, 2400, 48));
    }

    /** The ceremony window is the rescue window, so a walk that gives up sooner is reported. */
    @Test
    void anEscortThatGivesUpBeforeTheCeremonyCouldFinishIsReported() {
        assertTrue(ConfigValidator.validateCapitalPunishment(true, true, 2400, true, true, false, 1200, 48)
                .stream().anyMatch(p -> p.contains("gives up before the ceremony")));
        assertEquals(List.of(), ConfigValidator.validateCapitalPunishment(true, true, 1200, true,
                true, false, 1200, 48), "equal is allowed: the walk may take exactly the window");
    }

    @Test
    void aCapitalFeatureWithNoQualifyingOffenceIsReported() {
        assertTrue(ConfigValidator.validateCapitalPunishment(true, true, 1200, true, false, false, 2400, 48)
                .stream().anyMatch(p -> p.contains("nothing can qualify")));
        assertTrue(ConfigValidator.validateCapitalPunishment(true, true, 0, true, true, false, 2400, 48)
                .stream().anyMatch(p -> p.contains("no rescue or pardon window")));
    }

    // --- compatibility and presets ---

    @Test
    void theShippedCompatibilityGroupIsClean() {
        assertEquals(List.of(), ConfigValidator.validateCompatibility("WARN", true, true, false));
    }

    @Test
    void anUnknownCoexistencePolicyIsReportedRatherThanGuessed() {
        assertTrue(ConfigValidator.validateCompatibility("IGNORE", true, true, false).stream()
                .anyMatch(p -> p.contains("must be WARN or REFUSE")));
        assertTrue(ConfigValidator.validateCompatibility("REFUSE", true, true, true).stream()
                .anyMatch(p -> p.contains("applies no new restraints")),
                "refusing with the donor installed is worth saying out loud");
        assertTrue(ConfigValidator.validateCompatibility("REFUSE", true, true, false).isEmpty(),
                "refusing with nothing to refuse changes nothing and is not worth a line");
    }

    @Test
    void switchingEveryOptionalAdapterOffIsReported() {
        assertTrue(ConfigValidator.validateCompatibility("WARN", false, true, false).stream()
                .anyMatch(p -> p.contains("optionalAdaptersEnabled")));
        assertTrue(ConfigValidator.validateCompatibility("WARN", true, false, false).stream()
                .anyMatch(p -> p.contains("reportAdapterVersions")));
    }

    @Test
    void theShippedPresetPairIsClean() {
        assertEquals(List.of(), ConfigValidator.validatePreset("CUFFED_PARITY", "CUFFED_PARITY"));
        assertFalse(ConfigValidator.validatePreset("CUFFED_PARITY", "SOMETHING_ELSE").isEmpty());
    }

}
