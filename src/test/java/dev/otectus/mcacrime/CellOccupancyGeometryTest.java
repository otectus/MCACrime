package dev.otectus.mcacrime;

import dev.otectus.mcacrime.jail.CellBlueprint;
import dev.otectus.mcacrime.jail.CellOccupants;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import org.junit.jupiter.api.Test;
import java.util.HashSet;
import static org.junit.jupiter.api.Assertions.*;

class CellOccupancyGeometryTest {
    private final BlockPos anchor = new BlockPos(10, 64, -20);

    @Test void occupancyIncludesTheEmptyInteriorAndBodiesIntersectingBarsOrRoof() {
        var bounds = CellBlueprint.bounds(anchor);
        assertTrue(bounds.intersects(new AABB(10.2, 64, -19.8, 10.8, 65.8, -19.2)));
        assertTrue(bounds.intersects(new AABB(7.8, 64, -20, 8.4, 65.8, -19.4)));
        assertTrue(bounds.intersects(new AABB(10.2, 67, -19.8, 10.8, 68.8, -19.2)));
        assertFalse(bounds.intersects(new AABB(14, 64, -20, 14.6, 65.8, -19.4)));
    }

    @Test void intakePerimeterHasUniqueStandsOutsideTheWholeStructure() {
        var positions = CellOccupants.perimeter(anchor, CellBlueprint.RADIUS + 1);
        assertEquals(24, positions.size());
        assertEquals(positions.size(), new HashSet<>(positions).size());
        for (var feet : positions) {
            var body = new AABB(feet.getX() + 0.2, feet.getY(), feet.getZ() + 0.2,
                    feet.getX() + 0.8, feet.getY() + 1.8, feet.getZ() + 0.8);
            assertFalse(CellBlueprint.bounds(anchor).intersects(body));
        }
    }
}
