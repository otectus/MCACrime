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
 * inventory showing Karma / band / Heat / Wanted. Client + Forge bus only.
 *
 * <p>The button is a real widget, added through {@code Init.Post}, so focus, narration and click
 * routing are vanilla's. The card itself is still drawn from {@code Render.Post}, and that split is
 * deliberate rather than an oversight: {@code EffectRenderingInventoryScreen} draws the
 * potion-effect column beside the GUI from inside its own {@code render()}, so a card drawn as a
 * widget would be painted over by it. Drawing after the whole screen is what keeps the card on top.
 */
@Mod.EventBusSubscriber(modid = McaCrime.MOD_ID, value = Dist.CLIENT)
public final class PlayerCardScreenHooks {

    /** Button offset from the inventory's top-left, at the bottom-left of the player-model box. */
    private static final int BTN_OFFSET_X = 26;
    private static final int BTN_OFFSET_Y = 64;

    /** Whether the card is currently expanded; re-seeded from config each time the inventory opens. */
    private static boolean open;

    private PlayerCardScreenHooks() {
    }

    @SubscribeEvent
    public static void onInit(ScreenEvent.Init.Post event) {
        if (!(event.getScreen() instanceof InventoryScreen screen)
                || !McaCrimeConfig.CLIENT.showPlayerCardButton.get()) {
            return;
        }
        open = McaCrimeConfig.CLIENT.playerCardOpenByDefault.get();
        event.addListener(new PlayerCardButton(screen, BTN_OFFSET_X, BTN_OFFSET_Y));
    }

    @SubscribeEvent
    public static void onRender(ScreenEvent.Render.Post event) {
        if (!(event.getScreen() instanceof InventoryScreen screen)
                || !McaCrimeConfig.CLIENT.showPlayerCardButton.get()) {
            return;
        }
        if (open) {
            PlayerCardPanel.renderCard(event.getGuiGraphics(), screen);
        }
    }

    /** Whether the card is showing, so the button can draw itself pressed in. */
    static boolean isOpen() {
        return open;
    }

    static void toggle() {
        open = !open;
    }
}
