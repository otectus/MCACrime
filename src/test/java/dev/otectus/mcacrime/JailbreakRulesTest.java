package dev.otectus.mcacrime;

import dev.otectus.mcacrime.config.ConfigValidator;
import dev.otectus.mcacrime.jail.ContainmentHandler;
import dev.otectus.mcacrime.jail.HoldingCell;
import dev.otectus.mcacrime.jail.HoldingCellService;
import dev.otectus.mcacrime.jail.JailConfine;
import dev.otectus.mcacrime.jail.JailContainmentMode;
import dev.otectus.mcacrime.jail.JailState;
import dev.otectus.mcacrime.state.world.CrimeWorldData;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Breaking out of jail under {@code jailContainmentMode} (0.7.7): which breaks the jail refuses, what
 * happens to a prisoner found outside, which mode is in force, and which blocks are never loot.
 *
 * <p>The live paths need a player and a world; these are the rules they ask, each a pure function so the
 * whole table can be read in one place.
 */
class JailbreakRulesTest {

    private static final ResourceLocation OVERWORLD = new ResourceLocation("minecraft", "overworld");
    private static final ResourceLocation NETHER = new ResourceLocation("minecraft", "the_nether");
    private static final UUID PRISONER = UUID.fromString("00000000-0000-0000-0000-0000000000c1");
    private static final BlockPos ANCHOR = new BlockPos(40, 64, 40);
    private static final BlockPos WALL = new BlockPos(41, 64, 40);
    private static final BlockPos ROOF = new BlockPos(40, 67, 40);
    private static final BlockPos FIELD = new BlockPos(60, 64, 40);

    // --- which breaks the jail refuses ---------------------------------------------------------------------

    @Test
    void containmentRefusesEveryBreakInsideTheRegion() {
        assertTrue(ContainmentHandler.refuses(JailContainmentMode.CONTAINMENT, false, true));
        assertTrue(ContainmentHandler.refuses(JailContainmentMode.REINFORCED, false, true),
                "REINFORCED still behaves as CONTAINMENT");
    }

    @Test
    void physicalLetsAPrisonerBreakTheirWayOut() {
        assertFalse(ContainmentHandler.refuses(JailContainmentMode.PHYSICAL, false, true));
    }

    @Test
    void theJailHasNoOpinionOutsideItsRegionOrAboutSomebodyAlreadyOutOfLawfulHands() {
        for (JailContainmentMode mode : JailContainmentMode.values()) {
            assertFalse(ContainmentHandler.refuses(mode, false, false), "outside the region, " + mode);
            assertFalse(ContainmentHandler.refuses(mode, true, true), "after a cuff escape, " + mode);
        }
    }

    // --- a prisoner found outside ---------------------------------------------------------------------------

    @Test
    void walkingOutOfAPhysicalJailIsAnEscapeAndNeverASnapBack() {
        assertEquals(JailConfine.Outside.ESCAPE, JailConfine.outside(JailContainmentMode.PHYSICAL, false, false));
        assertEquals(JailConfine.Outside.ALREADY_OUT,
                JailConfine.outside(JailContainmentMode.PHYSICAL, false, true), "charged once, then left alone");
    }

    @Test
    void anEscapeeIsNotPulledBackAfterTheServerSwitchesToContainment() {
        for (JailContainmentMode mode : JailContainmentMode.values()) {
            for (boolean breached : new boolean[]{false, true}) {
                assertEquals(JailConfine.Outside.ALREADY_OUT, JailConfine.outside(mode, breached, true),
                        mode + (breached ? " breached" : " held"));
            }
        }
    }

    @Test
    void aContainmentPrisonerWhoStraysIsPutBackUnlessTheDoorGaveWay() {
        assertEquals(JailConfine.Outside.RETURN, JailConfine.outside(JailContainmentMode.CONTAINMENT, false, false));
        assertEquals(JailConfine.Outside.RETURN, JailConfine.outside(JailContainmentMode.REINFORCED, false, false));
        assertEquals(JailConfine.Outside.ESCAPE, JailConfine.outside(JailContainmentMode.CONTAINMENT, true, false),
                "a picked padlock is an escape in every mode");
        assertEquals(JailConfine.Outside.RETURN, JailConfine.outside(null, false, false),
                "an unreadable mode is the anti-grief default");
    }

    // --- which mode is in force -----------------------------------------------------------------------------

    @Test
    void theLiveSettingDecidesAndTheSnapshotOnlyStandsInWithoutOne() {
        // On a server the live setting governs, whatever the sentence was snapshotted with; where no
        // config is loaded (this suite on Forge) the snapshot answers instead.
        JailState physical = new JailState(100L, ANCHOR, OVERWORLD, 4, JailContainmentMode.PHYSICAL);
        JailContainmentMode live;
        try {
            live = McaCrimeConfig.COMMON.jailContainmentMode.get();
        } catch (IllegalStateException notLoaded) {
            live = null;
        }
        assertEquals(live == null ? JailContainmentMode.PHYSICAL : live, JailConfine.mode(physical));
        assertEquals(live == null ? JailContainmentMode.CONTAINMENT : live, JailConfine.mode(null));
    }

    // --- built cells are never loot -------------------------------------------------------------------------

    private static HoldingCell cellOver(BlockPos... built) {
        Map<BlockPos, BlockState> replaced = new HashMap<>();
        Map<BlockPos, BlockState> placed = new HashMap<>();
        for (BlockPos pos : built) {
            replaced.put(pos, null);
            placed.put(pos, null);
        }
        return new HoldingCell(PRISONER, UUID.randomUUID(), ANCHOR, OVERWORLD, 3, 0L, replaced, placed);
    }

    @Test
    void aBlockACellPutUpIsGeneratedAndNothingElseIs() {
        CrimeWorldData data = new CrimeWorldData();
        data.putHoldingCell(cellOver(WALL, ROOF));

        assertTrue(HoldingCellService.generatedBlockAt(data, OVERWORLD, WALL));
        assertTrue(HoldingCellService.generatedBlockAt(data, OVERWORLD, ROOF));
        assertFalse(HoldingCellService.generatedBlockAt(data, OVERWORLD, FIELD), "somebody's own wall drops");
        assertFalse(HoldingCellService.generatedBlockAt(data, NETHER, WALL), "the same coordinate elsewhere");
        assertFalse(HoldingCellService.generatedBlockAt(null, OVERWORLD, WALL));
    }

    @Test
    void whatIsLeftOfAHalfDemolishedCellIsStillGenerated() {
        CrimeWorldData data = new CrimeWorldData();
        data.putHoldingCell(cellOver(WALL, ROOF));

        // The roof's chunk is asleep, so only the wall comes back; the roof waits in the journal.
        HoldingCellService.dismantle(data, PRISONER, cell -> Set.of(ROOF));

        assertFalse(HoldingCellService.generatedBlockAt(data, OVERWORLD, WALL), "restored terrain is terrain");
        assertTrue(HoldingCellService.generatedBlockAt(data, OVERWORLD, ROOF),
                "a block still waiting to be taken down is the mod's until it is");
    }

    // --- the validator says what a combination does ---------------------------------------------------------

    @Test
    void physicalWithHardContainmentWarnsThatOnlyThePadlockOpensABuiltCell() {
        List<String> problems = ConfigValidator.validateJailbreak(JailContainmentMode.PHYSICAL,
                "HARD_CONTAINMENT", false, true);
        assertEquals(1, problems.size());
        assertTrue(problems.get(0).contains("padlock"), problems.get(0));
    }

    @Test
    void physicalWithAuthorisedRemovalOnlyWarnsToo() {
        List<String> problems = ConfigValidator.validateJailbreak(JailContainmentMode.PHYSICAL,
                "PICKAXE_QUALIFIED", true, false);
        assertEquals(1, problems.size());
        assertFalse(problems.get(0).contains("padlock"), "no built cell, so nothing to say about one");
    }

    @Test
    void theShippedAndOrdinaryCombinationsAreQuiet() {
        assertEquals(List.of(), ConfigValidator.validateJailbreak(JailContainmentMode.CONTAINMENT,
                "HARD_CONTAINMENT", true, true), "containment never needed a wall to break");
        assertEquals(List.of(), ConfigValidator.validateJailbreak(JailContainmentMode.PHYSICAL,
                "PICKAXE_QUALIFIED", false, true));
    }
}
