package dev.otectus.mcacrime;

import dev.otectus.mcacrime.menu.MaskStationRoute;
import net.minecraft.world.inventory.ClickType;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The extraction-route table (0.7.2 §8.3), asserted rather than assumed.
 *
 * <p>Spec §8.3 wants every route either traced or explicitly denied, because the route nobody thought
 * about is the one that treats a virtual preview as a real stack. So the table is exhaustive, this
 * test says so, and adding a {@link ClickType} to the game without deciding what the station does with
 * it fails here rather than in somebody's world.
 */
class MaskStationRouteTest {

    private static final Set<MaskStationRoute> ALLOWED = EnumSet.of(
            MaskStationRoute.PICKUP, MaskStationRoute.RIGHT_CLICK, MaskStationRoute.SHIFT_CLICK);

    @Test
    void onlyTheThreeCommittedRoutesAreAllowed() {
        for (MaskStationRoute route : MaskStationRoute.values()) {
            assertEquals(ALLOWED.contains(route), route.allowed(), route + " changed side");
        }
    }

    @Test
    void everyDeniedRouteIsNamedRatherThanLeftToAFallback() {
        for (MaskStationRoute route : EnumSet.complementOf(EnumSet.copyOf(ALLOWED))) {
            assertFalse(route.allowed(), route + " must be denied");
        }
        assertTrue(EnumSet.complementOf(EnumSet.copyOf(ALLOWED)).containsAll(EnumSet.of(
                MaskStationRoute.HOTBAR_SWAP, MaskStationRoute.OFFHAND_SWAP, MaskStationRoute.DROP,
                MaskStationRoute.DRAG, MaskStationRoute.DOUBLE_CLICK, MaskStationRoute.CREATIVE_CLONE,
                MaskStationRoute.PLACE)));
    }

    @Test
    void everyVanillaClickTypeMapsToSomething() {
        for (ClickType type : ClickType.values()) {
            assertNotNull(MaskStationRoute.of(type, 0), type + " has no route");
        }
    }

    @Test
    void leftAndRightClickAreDistinguishedButBothCraft() {
        assertEquals(MaskStationRoute.PICKUP, MaskStationRoute.of(ClickType.PICKUP, 0));
        assertEquals(MaskStationRoute.RIGHT_CLICK, MaskStationRoute.of(ClickType.PICKUP, 1));
        assertTrue(MaskStationRoute.of(ClickType.PICKUP, 0).allowed());
        assertTrue(MaskStationRoute.of(ClickType.PICKUP, 1).allowed());
    }

    @Test
    void theOffHandSwapIsNotMistakenForAHotbarSwap() {
        assertEquals(MaskStationRoute.OFFHAND_SWAP, MaskStationRoute.of(ClickType.SWAP, 40));
        assertEquals(MaskStationRoute.HOTBAR_SWAP, MaskStationRoute.of(ClickType.SWAP, 3));
        assertFalse(MaskStationRoute.of(ClickType.SWAP, 40).allowed());
        assertFalse(MaskStationRoute.of(ClickType.SWAP, 3).allowed());
    }

    @Test
    void theRoutesThatWouldMintAFreeMaskAreDenied() {
        assertFalse(MaskStationRoute.of(ClickType.PICKUP_ALL, 0).allowed(), "double-click collect");
        assertFalse(MaskStationRoute.of(ClickType.CLONE, 0).allowed(), "creative clone");
        assertFalse(MaskStationRoute.of(ClickType.QUICK_CRAFT, 0).allowed(), "drag");
        assertFalse(MaskStationRoute.of(ClickType.THROW, 0).allowed(), "drop from the result slot");
    }

    @Test
    void shiftClickIsTheOneBatchRoute() {
        assertEquals(MaskStationRoute.SHIFT_CLICK, MaskStationRoute.of(ClickType.QUICK_MOVE, 0));
        assertTrue(MaskStationRoute.SHIFT_CLICK.allowed());
    }
}
