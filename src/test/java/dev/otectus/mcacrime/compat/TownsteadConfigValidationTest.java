package dev.otectus.mcacrime.compat;

import dev.otectus.mcacrime.config.ConfigValidator;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The one rule {@code /crime validate} owes an operator about Townstead: a switch that is on while
 * the capability behind it is missing reads as <b>degraded</b>, never as off and never as silence.
 *
 * <p>"Off" is a decision somebody made. "Degraded" is a fact about the mods installed. Letting the
 * second look like the first is how a server owner ends up believing a protection is in force while
 * nothing is doing it.
 */
class TownsteadConfigValidationTest {

    private static Map<String, Boolean> allOff() {
        Map<String, Boolean> switches = new LinkedHashMap<>();
        for (TownsteadDiagnostics.SwitchRequirement requirement : TownsteadDiagnostics.REQUIREMENTS) {
            switches.put(requirement.setting(), false);
        }
        return switches;
    }

    private static Predicate<TownsteadCapability> bound(TownsteadCapability... capabilities) {
        Set<TownsteadCapability> live = Set.of(capabilities);
        return live::contains;
    }

    @Test
    void anAbsentTownsteadIsNeverAProblem() {
        Map<String, Boolean> switches = allOff();
        switches.put("respectIncapacity", true);
        switches.put("publicReactions", true);

        assertEquals(List.of(), ConfigValidator.validateTownstead(
                false, true, switches, bound(), 20, 40, 96, 24),
                "Townstead is absent on nearly every install; reporting a dozen lines about it would "
                        + "bury the problems that matter");
    }

    @Test
    void aSwitchOnWithItsCapabilityMissingIsReportedAsDegraded() {
        Map<String, Boolean> switches = allOff();
        switches.put("equipmentProvenance", true);

        List<String> problems = ConfigValidator.validateTownstead(
                true, true, switches, bound(TownsteadCapability.READ_VILLAGER), 20, 40, 96, 24);

        assertEquals(1, problems.size(), problems.toString());
        assertTrue(problems.get(0).contains("townstead.equipmentProvenance"));
        assertTrue(problems.get(0).contains(TownsteadCapability.EQUIPMENT_PROVENANCE.id()),
                "the message must name the missing capability, or nobody can act on it");
        assertTrue(problems.get(0).contains("DEGRADED"),
                "the word matters: 'off' would read as the operator's own choice");
    }

    @Test
    void aSwitchWithEveryCapabilityBoundIsSilent() {
        Map<String, Boolean> switches = allOff();
        switches.put("respectIncapacity", true);

        assertEquals(List.of(), ConfigValidator.validateTownstead(true, true, switches,
                bound(TownsteadCapability.READ_NEEDS, TownsteadCapability.STAGE_CAPABILITIES), 20, 40,
                96, 24));
    }

    @Test
    void aSwitchThatIsOffIsSilentEvenWithNothingBound() {
        assertEquals(List.of(), ConfigValidator.validateTownstead(true, true, allOff(), bound(), 20, 40, 96, 24));
    }

    @Test
    void theMasterSwitchBeingOffIsSaidOnceRatherThanPerSetting() {
        Map<String, Boolean> switches = allOff();
        switches.put("respectIncapacity", true);
        switches.put("publicReactions", true);

        List<String> problems = ConfigValidator.validateTownstead(true, false, switches, bound(), 20, 40, 96, 24);

        assertEquals(1, problems.size(), problems.toString());
        assertTrue(problems.get(0).contains("townstead.enabled is false"));
        assertTrue(problems.get(0).contains("2"), "it should say how many settings are inert");
    }

    /**
     * A lease shorter than the snapshot cache means a claim can lapse while the reading it was made
     * from is still being handed out — the decision outlives its own evidence.
     */
    @Test
    void aLeaseShorterThanTheSnapshotCacheIsReported() {
        List<String> problems = ConfigValidator.validateTownstead(
                false, true, allOff(), bound(), 60, 20, 96, 24);

        assertEquals(1, problems.size(), problems.toString());
        assertTrue(problems.get(0).contains("activityLeaseTicks"));
    }

    /**
     * A facility radius under the temporary-cell radius inverts the ladder the operator configured: an
     * assigned cell further out is skipped and a cage is dug next to the jail somebody built.
     */
    @Test
    void aFacilityRadiusUnderTheTemporaryCellRadiusIsReported() {
        List<String> problems = ConfigValidator.validateTownstead(
                false, true, allOff(), bound(), 20, 40, 16, 64);

        assertEquals(1, problems.size(), problems.toString());
        assertTrue(problems.get(0).contains("facilitySearchRadius"));
    }

    /** Every switch in the table must be one the config actually declares, or validation lies. */
    @Test
    void everyRequirementNamesADeclaredSwitchAndACapability() {
        assertFalse(TownsteadDiagnostics.REQUIREMENTS.isEmpty());
        for (TownsteadDiagnostics.SwitchRequirement requirement : TownsteadDiagnostics.REQUIREMENTS) {
            assertFalse(requirement.setting().isBlank());
            assertFalse(requirement.summary().isBlank(),
                    requirement.setting() + " has no summary; the degraded message would explain nothing");
            assertFalse(requirement.capabilities().isEmpty(),
                    requirement.setting() + " needs no capability, so it could never be degraded -- either "
                            + "it does not belong in this table or its requirement was dropped");
        }
    }

    /** The four-word vocabulary, checked directly rather than through a validation message. */
    @Test
    void featureStateDistinguishesOffFromDegraded() {
        TownsteadDiagnostics.SwitchRequirement requirement = TownsteadDiagnostics.REQUIREMENTS.stream()
                .filter(candidate -> candidate.setting().equals("publicReactions"))
                .findFirst().orElseThrow();

        assertEquals(TownsteadDiagnostics.FeatureState.OFF,
                TownsteadDiagnostics.stateOf(requirement, false, bound()));
        assertEquals(TownsteadDiagnostics.FeatureState.DEGRADED,
                TownsteadDiagnostics.stateOf(requirement, true, bound()));
        assertEquals(TownsteadDiagnostics.FeatureState.ACTIVE,
                TownsteadDiagnostics.stateOf(requirement, true, bound(TownsteadCapability.DISPATCH_REACTION)));
    }
}
