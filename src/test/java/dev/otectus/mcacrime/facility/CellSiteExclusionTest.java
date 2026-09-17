package dev.otectus.mcacrime.facility;

import dev.otectus.mcacrime.compat.TownsteadBuildingView;
import dev.otectus.mcacrime.jail.CellBlueprint;
import dev.otectus.mcacrime.jail.CellBuilder;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Where a temporary cell may not go, and what happens when nobody can say.
 *
 * <p>The exclusion is tested as geometry rather than through a level, which is the point: the rule is
 * that the whole footprint must miss every recognised building, not merely that the probe point did.
 * A building clipping one corner of the cell wall is still a building the cage is being dug into.
 *
 * <p>The second half is the one §8.5 insists on. "I could not check" is not "there is nothing there",
 * and an automatic build on that assumption lands in a village's granary — so the unanswerable case is
 * a refusal that carries a reason, not a silent success.
 */
class CellSiteExclusionTest {

    private static TownsteadBuildingView building(int minX, int minY, int minZ,
                                                  int maxX, int maxY, int maxZ) {
        return new TownsteadBuildingView(7, 3, "kitchen_l1", 0,
                (minX + maxX) / 2, (minY + maxY) / 2, (minZ + maxZ) / 2,
                minX, minY, minZ, maxX, maxY, maxZ, "building", 12);
    }

    @Test
    void aFootprintInsideABuildingIsRejected() {
        BlockPos anchor = new BlockPos(0, 64, 0);

        assertTrue(CellBuilder.overlapsFootprint(List.of(building(-4, 60, -4, 4, 70, 4)), anchor));
    }

    @Test
    void aBuildingClippingOneCornerIsStillAnOverlap() {
        BlockPos anchor = new BlockPos(0, 64, 0);
        int r = CellBlueprint.RADIUS;

        // Touches exactly the far corner column of the footprint and nothing else.
        TownsteadBuildingView corner = building(r, 64, r, r + 6, 70, r + 6);

        assertTrue(CellBuilder.overlapsFootprint(List.of(corner), anchor),
                "testing only the block that found the building would let this through");
    }

    @Test
    void aBuildingOneBlockClearIsNotAnOverlap() {
        BlockPos anchor = new BlockPos(0, 64, 0);
        int r = CellBlueprint.RADIUS;

        assertFalse(CellBuilder.overlapsFootprint(
                List.of(building(r + 1, 64, r + 1, r + 6, 70, r + 6)), anchor));
    }

    @Test
    void aBuildingAboveOrBelowTheCellIsNotAnOverlap() {
        BlockPos anchor = new BlockPos(0, 64, 0);

        assertFalse(CellBuilder.overlapsFootprint(
                List.of(building(-4, 80, -4, 4, 90, 4)), anchor),
                "a building on the hill above is not a building the cell is being dug into");
        assertFalse(CellBuilder.overlapsFootprint(
                List.of(building(-4, 40, -4, 4, 50, 4)), anchor));
    }

    @Test
    void noBuildingsMeansNoExclusion() {
        assertFalse(CellBuilder.overlapsFootprint(List.of(), new BlockPos(0, 64, 0)));
        assertFalse(CellBuilder.overlapsFootprint(null, new BlockPos(0, 64, 0)));
        assertFalse(CellBuilder.overlapsFootprint(List.of(building(0, 0, 0, 1, 1, 1)), null));
    }

    @Test
    void theFootprintTestedIsTheWholeBlueprintHeight() {
        BlockPos anchor = new BlockPos(0, 64, 0);

        // A building occupying only the course the cell roof lands on.
        assertTrue(CellBuilder.overlapsFootprint(List.of(building(-1, 64 + CellBlueprint.highestOffset(),
                -1, 1, 64 + CellBlueprint.highestOffset(), 1)), anchor));
        // ...and only the course the floor is laid in.
        assertTrue(CellBuilder.overlapsFootprint(List.of(building(-1, 64 + CellBlueprint.lowestOffset(),
                -1, 1, 64 + CellBlueprint.lowestOffset(), 1)), anchor));
    }

    @Test
    void anUnanswerableCheckIsARefusalWithAReasonRatherThanASilentBuild() {
        CellBuilder.Outcome outcome = new CellBuilder.Outcome(null, CellBuilder.Refusal.UNANSWERABLE);

        assertFalse(outcome.built(), "a cell must not be raised on a question nobody answered");
        assertTrue(outcome.describe().contains("could not be read"));
        assertTrue(outcome.describe().contains("/crime"),
                "the reason has to tell the operator what to do about it");
    }

    @Test
    void everyRefusalSaysSomethingDifferent() {
        for (CellBuilder.Refusal refusal : CellBuilder.Refusal.values()) {
            String described = new CellBuilder.Outcome(null, refusal).describe();
            assertFalse(described.isBlank(), refusal + " has no explanation");
            for (CellBuilder.Refusal other : CellBuilder.Refusal.values()) {
                if (other != refusal) {
                    assertFalse(described.equals(new CellBuilder.Outcome(null, other).describe()),
                            refusal + " and " + other + " read identically, so the difference is invisible");
                }
            }
        }
    }

    @Test
    void aSettlementRefusalIsNotATerrainRefusal() {
        assertEquals(CellBuilder.Refusal.SETTLEMENT_BUILDING,
                new CellBuilder.Outcome(null, CellBuilder.Refusal.SETTLEMENT_BUILDING).refusal());
        assertTrue(new CellBuilder.Outcome(null, CellBuilder.Refusal.SETTLEMENT_BUILDING)
                        .describe().contains("excludeWorksitesFromTemporaryCells"),
                "walking somewhere else does not fix a configured exclusion; the operator has to know "
                        + "which switch produced it");
    }
}
