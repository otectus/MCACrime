package dev.otectus.mcacrime.client;

import dev.otectus.mcacrime.McaCrime;
import dev.otectus.mcacrime.McaCrimeConfig;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Injects the reputation "player card" into the survival inventory (per the user's design): a small
 * toggle button at the bottom-left of the player-model box opens a card panel to the left of the
 * inventory showing Karma / band / Heat / Wanted. Implemented with Forge {@link ScreenEvent}s and
 * manual drawing/hit-testing (no mixin needed in 0.1.0). Client + Forge bus only.
 */
@Mod.EventBusSubscriber(modid = McaCrime.MOD_ID, value = Dist.CLIENT)
public final class PlayerCardScreenHooks {

    /** Button offset from the inventory's top-left, at the bottom-left of the player-model box. */
    private static final int BTN_OFFSET_X = 26;
    private static final int BTN_OFFSET_Y = 64;
    static final int BTN_SIZE = 11;

    /** Whether the card is currently expanded; re-seeded from config each time the inventory opens. */
    private static boolean open;

    private PlayerCardScreenHooks() {
    }

    @SubscribeEvent
    public static void onInit(ScreenEvent.Init.Post event) {
        if (event.getScreen() instanceof InventoryScreen) {
            open = McaCrimeConfig.CLIENT.playerCardOpenByDefault.get();
        }
    }

    @SubscribeEvent
    public static void onRender(ScreenEvent.Render.Post event) {
        if (!(event.getScreen() instanceof InventoryScreen screen) || !McaCrimeConfig.CLIENT.showPlayerCardButton.get()) {
            return;
        }
        int bx = screen.getGuiLeft() + BTN_OFFSET_X;
        int by = screen.getGuiTop() + BTN_OFFSET_Y;
        boolean hover = within(event.getMouseX(), event.getMouseY(), bx, by);
        PlayerCardPanel.renderButton(event.getGuiGraphics(), bx, by, BTN_SIZE, hover, open);
        if (open) {
            PlayerCardPanel.renderCard(event.getGuiGraphics(), screen);
        }
    }

    @SubscribeEvent
    public static void onClick(ScreenEvent.MouseButtonPressed.Pre event) {
        if (!(event.getScreen() instanceof InventoryScreen screen) || !McaCrimeConfig.CLIENT.showPlayerCardButton.get()) {
            return;
        }
        if (event.getButton() != 0) {
            return;
        }
        int bx = screen.getGuiLeft() + BTN_OFFSET_X;
        int by = screen.getGuiTop() + BTN_OFFSET_Y;
        if (within(event.getMouseX(), event.getMouseY(), bx, by)) {
            open = !open;
            event.setCanceled(true); // consume the click so it doesn't fall through to a slot
        }
    }

    private static boolean within(double mx, double my, int x, int y) {
        return mx >= x && mx < x + BTN_SIZE && my >= y && my < y + BTN_SIZE;
    }
}
