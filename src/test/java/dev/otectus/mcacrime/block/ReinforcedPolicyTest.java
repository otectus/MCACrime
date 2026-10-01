package dev.otectus.mcacrime.block;

import dev.otectus.mcacrime.TestPaths;
import dev.otectus.mcacrime.block.prison.ReinforcedBarsShape;
import dev.otectus.mcacrime.block.prison.ReinforcedBreakingPolicy;
import dev.otectus.mcacrime.block.prison.ReinforcedPolicy;
import dev.otectus.mcacrime.config.ConfigValidator;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Reinforced blocks resist honestly, and the documentation says what the code does (M5.4, spec §12.1).
 *
 * <p>The specification's instruction is blunt: "Do not describe a tool-breakable block as absolutely
 * unbreakable." So the test is in two halves. The first is the rule itself, which is pure. The second
 * is the wording, because the defect this milestone is correcting is as much a documentation defect
 * as a code one.
 */
class ReinforcedPolicyTest {

    @Test
    void pickaxeQualifiedLetsAnybodyThroughEventually() {
        assertTrue(ReinforcedPolicy.mayBreak(ReinforcedBreakingPolicy.PICKAXE_QUALIFIED, false, false, false));
        assertTrue(ReinforcedPolicy.mayBreak(ReinforcedBreakingPolicy.PICKAXE_QUALIFIED, true, false, false),
                "under the default policy a prisoner may work at a wall; the jail system decides what "
                        + "escaping through it means");
        assertTrue(ReinforcedBreakingPolicy.PICKAXE_QUALIFIED.allowsPrisonerBreaking());
    }

    @Test
    void hardContainmentStopsPrisonersAndOnlyPrisoners() {
        assertFalse(ReinforcedPolicy.mayBreak(ReinforcedBreakingPolicy.HARD_CONTAINMENT, true, false, false));
        assertTrue(ReinforcedPolicy.mayBreak(ReinforcedBreakingPolicy.HARD_CONTAINMENT, false, false, false),
                "a free player with an iron pickaxe still takes the wall apart");
        assertFalse(ReinforcedBreakingPolicy.HARD_CONTAINMENT.allowsPrisonerBreaking());
    }

    @Test
    void authorisedRemovalIsASeparateQuestionFromContainment() {
        assertFalse(ReinforcedPolicy.mayBreak(ReinforcedBreakingPolicy.PICKAXE_QUALIFIED, false, false, true));
        assertTrue(ReinforcedPolicy.mayBreak(ReinforcedBreakingPolicy.PICKAXE_QUALIFIED, false, true, true));
        // And it outranks containment: an operator clearing a cell is not a prisoner escaping one.
        assertTrue(ReinforcedPolicy.mayBreak(ReinforcedBreakingPolicy.HARD_CONTAINMENT, false, true, true));
    }

    @Test
    void anUnknownPolicyNameFallsBackToTheHonestDefault() {
        assertTrue(ReinforcedBreakingPolicy.parse("PICKAXE_QUALIFIED").isPresent());
        assertTrue(ReinforcedBreakingPolicy.parse("  hard_containment ").isPresent());
        assertTrue(ReinforcedBreakingPolicy.parse("unbreakable").isEmpty());
        assertTrue(ReinforcedBreakingPolicy.parse(null).isEmpty());
        // A null policy handed to the rule behaves as the default rather than throwing.
        assertTrue(ReinforcedPolicy.mayBreak(null, true, false, false));
    }

    @Test
    void theValidatorNamesABadPolicyAndDescribesWhatTheBlocksActuallyDo() {
        List<String> problems = ConfigValidator.validatePrisonConstruction("unbreakable", true, true,
                false);
        assertTrue(problems.stream().anyMatch(p -> p.contains("reinforcedBreakingPolicy")));

        // The honest summary when nothing is actually resisted.
        List<String> weak = ConfigValidator.validatePrisonConstruction("PICKAXE_QUALIFIED", false, false,
                false);
        assertTrue(weak.stream().anyMatch(p -> p.contains("it is not containment")));
    }

    @Test
    void nothingClaimsTheBlocksAreUnbreakable() {
        // The word may appear, but only inside a denial. That is the specification's rule: a
        // tool-breakable block must never be described as absolutely unbreakable, and the safest way
        // to keep that true is to require every occurrence to be a sentence saying it is not one.
        for (Path path : List.of(
                TestPaths.sources("dev", "otectus", "mcacrime", "block", "prison",
                        "ReinforcedBreakingPolicy.java"),
                TestPaths.sources("dev", "otectus", "mcacrime", "block", "prison",
                        "ReinforcedPolicy.java"),
                TestPaths.sources("dev", "otectus", "mcacrime", "block", "prison",
                        "PrisonBlockHandlers.java"),
                TestPaths.sources("dev", "otectus", "mcacrime", "config",
                        "ConfigValidator.java"))) {
            for (String line : read(path).split("\\R")) {
                String lower = line.toLowerCase(Locale.ROOT);
                if (!lower.contains("unbreakable")) {
                    continue;
                }
                assertTrue(lower.contains("not ") || lower.contains("never ") || lower.contains("no ")
                                || lower.contains("neither "),
                        path + " states 'unbreakable' as a claim: " + line.trim());
            }
        }
    }

    @Test
    void barsPickTheirColumnFromTheirOwnNeighbours() {
        assertEquals(0, ReinforcedBarsShape.column(false, false), "a single section is a cap");
        assertEquals(0, ReinforcedBarsShape.column(true, false), "a top section is a cap too");
        assertEquals(1, ReinforcedBarsShape.column(true, true), "between two, it is a middle");
        assertEquals(2, ReinforcedBarsShape.column(false, true), "with something below, it is a bottom");
    }

    @Test
    void barsJoinAFlatPanelAlongItsPlaneAndNeverThroughItsFace() {
        // A cell door facing north is a panel spanning west to east: bars beside it reach in from the
        // west and east, and bars in front of or behind it must not grow an arm into its face.
        net.minecraft.core.Direction.Axis northSouth = net.minecraft.core.Direction.Axis.Z;
        assertTrue(ReinforcedBarsShape.joinsPanel(northSouth, net.minecraft.core.Direction.EAST));
        assertTrue(ReinforcedBarsShape.joinsPanel(northSouth, net.minecraft.core.Direction.WEST));
        assertFalse(ReinforcedBarsShape.joinsPanel(northSouth, net.minecraft.core.Direction.NORTH));
        assertFalse(ReinforcedBarsShape.joinsPanel(northSouth, net.minecraft.core.Direction.SOUTH));
        assertFalse(ReinforcedBarsShape.joinsPanel(northSouth, net.minecraft.core.Direction.UP),
                "bars never join upward through a panel");
        assertFalse(ReinforcedBarsShape.joinsPanel(null, net.minecraft.core.Direction.EAST));
    }

    private static String read(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }
}
