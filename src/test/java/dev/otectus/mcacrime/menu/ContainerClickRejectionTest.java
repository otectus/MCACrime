package dev.otectus.mcacrime.menu;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * No vanilla click type can move anything through the frisking screen (M5.2, spec §11.2, §21.1).
 *
 * <p>The specification's requirement is that arbitrary slot insertion, double-click collection,
 * hotbar swaps, drag, throw and quick-move each either have defined safe behaviour or fail without
 * mutating. Here they all have the same defined safe behaviour: nothing. {@code clicked} is
 * overridden to an empty body, so every {@code ClickType} vanilla can deliver — and every forged one
 * a modified client can send — reaches a method that does not touch state.
 *
 * <p>A disabled control on a client is not enforcement, so none of this is asserted about the screen.
 * It is asserted about the menu, which is the server-side object every click actually arrives at.
 */
class ContainerClickRejectionTest {

    private static final Path MENU = Path.of("src", "main", "java", "dev", "otectus", "mcacrime",
            "menu", "FriskingMenu.java");
    private static final Path PROJECTION = Path.of("src", "main", "java", "dev", "otectus", "mcacrime",
            "menu", "FriskProjection.java");

    private static String read(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    @Test
    void forgedInventoryClickWhileArmRestrained() {
        String menu = read(MENU);
        // Every click type funnels into one override with an empty body.
        int clicked = menu.indexOf("public void clicked(int slotId, int button, @Nonnull ClickType clickType,");
        assertTrue(clicked > 0, "FriskingMenu must override clicked");
        String body = menu.substring(menu.indexOf('{', clicked) + 1,
                menu.indexOf('}', menu.indexOf('{', clicked) + 1));
        assertTrue(body.isBlank(), "clicked must do nothing at all, not 'nothing much': " + body);

        assertTrue(menu.contains("return ItemStack.EMPTY;"), "quickMoveStack moves nothing");
        assertTrue(menu.contains("public boolean canDragTo(@Nonnull Slot slot) {"), "no drag");
        assertTrue(menu.contains("public boolean mayPlace(@Nonnull ItemStack stack) {")
                        && menu.contains("public boolean mayPickup(@Nonnull Player player) {"),
                "every projected slot refuses both directions");
    }

    @Test
    void theProjectionsMutatorsAreAllInert() {
        String projection = read(PROJECTION);
        for (String method : new String[]{
                "public ItemStack removeItem(int index, int count) {",
                "public ItemStack removeItemNoUpdate(int index) {",
                "public void setItem(int index, @Nonnull ItemStack stack) {",
                "public void clearContent() {"}) {
            int at = projection.indexOf(method);
            assertTrue(at > 0, "FriskProjection must implement " + method);
        }
        // The one that matters most: upstream's clearContent empties the subject's whole inventory.
        int clear = projection.indexOf("public void clearContent() {");
        String body = projection.substring(projection.indexOf('{', clear) + 1,
                projection.indexOf('}', projection.indexOf('{', clear) + 1));
        assertTrue(body.isBlank(), "clearContent must not touch the subject's inventory: " + body);
        assertFalse(projection.contains("getInventory().clearContent()"),
                "the upstream defect must not be reproduced");

        assertTrue(projection.contains("FriskingService.stillValid(searcher, session)"),
                "stillValid asks a real question rather than returning true");
        assertFalse(projection.contains("return true;\n    }\n\n    @Override\n    public boolean canPlaceItem"),
                "stillValid must not be a constant");
    }

    @Test
    void theMenuRevalidatesTheWholeSessionEveryTick() {
        String menu = read(MENU);
        assertTrue(menu.contains("FriskingService.stillValid(searcher, session)"),
                "the menu's stillValid is the per-tick re-check of liveness, reach, restraint and "
                        + "custody generation");
        assertTrue(menu.contains("SessionCancelCause.MENU_CLOSED"),
                "closing the screen ends the session rather than leaving it open");
    }

    @Test
    void theLayoutFitsAndNeverOverlapsItsOwnTitle() {
        assertEquals(1, FriskingLayout.rows(1));
        assertEquals(1, FriskingLayout.rows(9));
        assertEquals(2, FriskingLayout.rows(10));
        assertEquals(FriskingLayout.MAX_ROWS, FriskingLayout.rows(200), "the panel stops growing");
        assertEquals(FriskingLayout.MAX_ROWS * FriskingLayout.COLUMNS, FriskingLayout.visible(200));
        for (int index = 0; index < FriskingLayout.visible(54); index++) {
            assertTrue(FriskingLayout.y(index) >= FriskingLayout.TOP,
                    "no slot may sit above the title line");
            assertTrue(FriskingLayout.x(index) + 16 <= FriskingLayout.WIDTH,
                    "no slot may run off the right edge");
        }
        assertTrue(FriskingLayout.height(54) > FriskingLayout.y(53) + 16,
                "the panel is tall enough for its last row");
    }
}
