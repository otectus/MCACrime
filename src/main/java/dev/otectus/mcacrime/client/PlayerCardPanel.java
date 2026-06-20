package dev.otectus.mcacrime.client;

import dev.otectus.mcacrime.crime.Band;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.network.chat.Component;

/**
 * Draws the reputation player card and its toggle button (spec §10.3, per the user's inventory-card
 * design). Pure rendering from {@link ClientSelfData}; no game state is touched. Client-only.
 */
public final class PlayerCardPanel {

    private static final int CARD_W = 108;
    private static final int CARD_H = 64;

    private PlayerCardPanel() {
    }

    /** The small toggle button at the bottom-left of the inventory's player-model box. */
    public static void renderButton(GuiGraphics g, int x, int y, int size, boolean hover, boolean open) {
        int bg = (hover || open) ? 0xFF6A6A6A : 0xFF3A3A3A;
        g.fill(x - 1, y - 1, x + size + 1, y + size + 1, 0xFF000000);
        g.fill(x, y, x + size, y + size, bg);
        // A band-colored dot communicates standing at a glance.
        int dot = 0xFF000000 | (NameColors.rgb(ClientSelfData.band()) & 0xFFFFFF);
        int cx = x + size / 2;
        int cy = y + size / 2;
        g.fill(cx - 2, cy - 2, cx + 2, cy + 2, dot);
    }

    /** The card panel, rendered just to the left of the inventory GUI. */
    public static void renderCard(GuiGraphics g, InventoryScreen screen) {
        Font font = Minecraft.getInstance().font;
        int x = Math.max(2, screen.getGuiLeft() - CARD_W - 4);
        int y = screen.getGuiTop();

        g.fill(x, y, x + CARD_W, y + CARD_H, 0xF0140414);      // translucent dark background
        g.fill(x, y, x + CARD_W, y + 2, 0xFF5C5CA0);            // top accent bar
        g.fill(x, y + CARD_H - 1, x + CARD_W, y + CARD_H, 0xFF000000);

        int tx = x + 6;
        int ty = y + 5;
        Band band = ClientSelfData.band();

        g.drawString(font, Component.translatable("mcacrime.card.title"), tx, ty, 0xFFFFFF, false);
        ty += 12;
        g.drawString(font, Component.translatable("mcacrime.card.karma", ClientSelfData.karma()), tx, ty, 0xC8C8C8, false);
        ty += 11;
        Component bandName = Component.translatable("mcacrime.band." + band.lower());
        g.drawString(font, Component.translatable("mcacrime.card.band", bandName), tx, ty, NameColors.rgb(band), false);
        ty += 11;
        g.drawString(font, Component.translatable("mcacrime.card.heat", ClientSelfData.heat()), tx, ty, 0xC8C8C8, false);
        ty += 11;
        boolean wanted = ClientSelfData.wanted();
        Component status = wanted
                ? Component.translatable("mcacrime.card.wanted")
                : Component.translatable("mcacrime.card.not_wanted");
        g.drawString(font, status, tx, ty, wanted ? NameColors.RED_RGB : 0x808080, false);
    }
}
