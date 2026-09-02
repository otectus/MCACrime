package dev.otectus.mcacrime;

import dev.otectus.mcacrime.jail.CellBlueprint;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The cell's geometry.
 *
 * <p>This is the only part of the block-placing code that can be tested without a world, and it happens
 * to be the part with the failure modes that matter: a hole in the cage, an interior with nowhere to
 * stand, and — the subtle one — a structure that does not fit inside the region
 * {@code JailRegion.contains} checks, which would leave part of the cage unprotected from the prisoner
 * holding a pickaxe.
 */
class CellBlueprintTest {

    @Test
    void everyPositionIsPlacedExactlyOnce() {
        List<CellBlueprint.Placement> placements = CellBlueprint.placements();
        Set<String> seen = new HashSet<>();
        for (CellBlueprint.Placement p : placements) {
            assertTrue(seen.add(p.dx() + "," + p.dy() + "," + p.dz()),
                    "duplicate position in the blueprint at " + p.dx() + "," + p.dy() + "," + p.dz());
        }
    }

    /**
     * The whole structure must fit inside the Chebyshev cube of {@link CellBlueprint#RADIUS} centred one
     * block above the anchor — which is the box {@code HoldingCell.toAnchor()} hands to the confinement
     * check. A roof outside that box is a roof {@code ContainmentHandler} will happily let a prisoner
     * mine through.
     */
    @Test
    void structureFitsTheConfinementRegion() {
        int r = CellBlueprint.RADIUS;
        for (CellBlueprint.Placement p : CellBlueprint.placements()) {
            int dyFromRegionCentre = p.dy() - 1; // the region is centred one above the anchor
            assertTrue(Math.abs(p.dx()) <= r && Math.abs(p.dz()) <= r && Math.abs(dyFromRegionCentre) <= r,
                    "placement at " + p.dx() + "," + p.dy() + "," + p.dz()
                            + " falls outside the confinement region");
        }
    }

    @Test
    void theInteriorIsOpenAndTallEnoughToStandIn() {
        long interior = CellBlueprint.placements().stream()
                .filter(p -> p.role() == CellBlueprint.Role.INTERIOR).count();
        int side = CellBlueprint.INTERIOR_RADIUS * 2 + 1;
        assertEquals((long) side * side * CellBlueprint.INTERIOR_HEIGHT, interior);
        assertTrue(CellBlueprint.INTERIOR_HEIGHT >= 2, "a prisoner has to fit standing up");
    }

    /** No gap in the walls: every non-interior column carries bars at every interior height. */
    @Test
    void theWallsAreComplete() {
        int r = CellBlueprint.RADIUS;
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                if (!CellBlueprint.isWallColumn(dx, dz)) {
                    continue;
                }
                for (int dy = 0; dy < CellBlueprint.INTERIOR_HEIGHT; dy++) {
                    int fx = dx;
                    int fy = dy;
                    int fz = dz;
                    assertTrue(CellBlueprint.placements().stream()
                                    .anyMatch(p -> p.dx() == fx && p.dy() == fy && p.dz() == fz
                                            && p.role() == CellBlueprint.Role.BARS),
                            "wall gap at " + fx + "," + fy + "," + fz);
                }
            }
        }
    }

    @Test
    void thereIsAFloorUnderTheWholeFootprintAndExactlyOneLight() {
        int side = CellBlueprint.RADIUS * 2 + 1;
        long floor = CellBlueprint.placements().stream()
                .filter(p -> p.role() == CellBlueprint.Role.FLOOR).count();
        long lights = CellBlueprint.placements().stream()
                .filter(p -> p.role() == CellBlueprint.Role.LIGHT).count();
        assertEquals((long) side * side, floor);
        assertEquals(1L, lights, "a dark cell is a mob spawner");
    }

    @Test
    void theInteriorIsClearedAfterTheWallsGoUp() {
        List<CellBlueprint.Placement> placements = CellBlueprint.placements();
        int lastStructural = -1;
        int firstInterior = Integer.MAX_VALUE;
        for (int i = 0; i < placements.size(); i++) {
            if (placements.get(i).role() == CellBlueprint.Role.INTERIOR) {
                firstInterior = Math.min(firstInterior, i);
            } else {
                lastStructural = Math.max(lastStructural, i);
            }
        }
        assertTrue(firstInterior > lastStructural,
                "the interior must be cleared last, or a wall block lands in space just opened");
    }

    @Test
    void wallColumnsAndInteriorColumnsDoNotOverlap() {
        assertFalse(CellBlueprint.isWallColumn(0, 0));
        assertTrue(CellBlueprint.isWallColumn(CellBlueprint.RADIUS, 0));
    }
}
