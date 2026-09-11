package dev.otectus.mcacrime;

import dev.otectus.mcacrime.config.ConfigValidator;
import dev.otectus.mcacrime.item.contraband.ContrabandRules;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The contraband list is validated, never fatal (0.7.0, plan §5.7).
 *
 * <p>One mistyped line must cost the operator that line and nothing else: not the rest of the list, and
 * certainly not the config load. Tags are the case that cannot be checked here at all — item tags do
 * not exist while a config file is read — so they are dropped later, by the same rule set, once tags
 * are bound.
 */
class ContrabandConfigValidationTest {

    private static List<String> validate(boolean enabled, List<String> items, String mode) {
        return ConfigValidator.validateContraband(enabled, items, mode, 0.15D, 40, 4.0D);
    }

    @Test
    void theShippedDefaultIsOffWithAnEmptyListAndThatIsValid() {
        assertFalse(McaCrimeConfig.COMMON.enableContraband.getDefault());
        assertEquals(List.of(), McaCrimeConfig.COMMON.illegalItems.getDefault());
        assertEquals(List.of(), validate(false, List.of(), "BOTH"));
        assertEquals(List.of(), validate(true, List.of(), "BOTH"));
    }

    @Test
    void goodIdsAndTagsPassAndABadIdIsReportedWithoutBeingFatal() {
        assertEquals(List.of(), validate(true, List.of("minecraft:tnt", "#mcacrime:illicit_goods",
                "somemod:strange_item"), "BOTH"));
        List<String> problems = validate(true, List.of("minecraft:tnt", "Not An Id"), "BOTH");
        assertEquals(1, problems.size(), problems.toString());
        assertTrue(problems.get(0).contains("contraband.illegalItems"), problems.toString());
    }

    @Test
    void aWildcardIsRefusedBecauseNothingWouldEverResolveIt() {
        List<String> problems = validate(true, List.of("minecraft:*"), "BOTH");
        assertTrue(problems.stream().anyMatch(p -> p.contains("wildcard")), problems.toString());
    }

    @Test
    void anUnknownDiscoveryModeIsReportedAndTheThreeKnownOnesAreNot() {
        assertTrue(validate(true, List.of(), "SOMETIMES").stream()
                .anyMatch(p -> p.contains("discoveryMode")));
        for (String mode : new String[]{"BOTH", "guard_patrol", "ARREST_ONLY"}) {
            assertEquals(List.of(), validate(true, List.of(), mode), mode);
        }
        assertTrue(ConfigValidator.isKnownDiscoveryMode("both"));
        assertFalse(ConfigValidator.isKnownDiscoveryMode(null));
    }

    @Test
    void aSearchChanceOfZeroIsReportedOnlyWhenPatrolSearchesAreTheOnlyWayToBeFound() {
        assertTrue(ConfigValidator.validateContraband(true, List.of(), "BOTH", 0.0D, 40, 4.0D).stream()
                .anyMatch(p -> p.contains("searchChance")));
        assertEquals(List.of(),
                ConfigValidator.validateContraband(true, List.of(), "ARREST_ONLY", 0.0D, 40, 4.0D));
        assertEquals(List.of(),
                ConfigValidator.validateContraband(false, List.of(), "BOTH", 0.0D, 40, 4.0D));
    }

    @Test
    void aTagThatDoesNotExistIsDroppedOnceTagsAreBoundAndReportedOnce() {
        ContrabandRules rules = ContrabandRules.compile(
                List.of("minecraft:tnt", "#mcacrime:illicit_goods", "#somemod:nonexistent"));
        assertEquals(2, rules.tags().size());
        assertEquals(List.of(), rules.problems());
        ContrabandRules filtered = rules.withTagsFiltered(
                tag -> tag.equals(ResourceLocation.fromNamespaceAndPath("mcacrime", "illicit_goods")));
        assertEquals(1, filtered.tags().size());
        assertEquals(1, filtered.ids().size(), "dropping a tag leaves the item ids alone");
        assertEquals(1, filtered.problems().size(), filtered.problems().toString());
        assertTrue(filtered.problems().get(0).contains("somemod:nonexistent"));
    }
}
