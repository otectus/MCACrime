package dev.otectus.mcacrime.client;

import dev.otectus.mcacrime.client.screen.CrimeSprites;
import dev.otectus.mcacrime.client.screen.PanelColours;
import dev.otectus.mcacrime.crime.Band;
import dev.otectus.mcacrime.util.TickFormat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.network.chat.Component;

/**
 * Draws the reputation player card (spec §10.3, per the user's inventory-card design). Pure
 * rendering from {@link ClientSelfData}; no game state is touched. Client-only.
 *
 * <p>It draws with {@link CrimeSprites} rather than with its own rectangles. It used to keep private
 * copies of the panel's background and accent colours, which is exactly how a card docked to the
 * inventory ends up looking like a different mod from the screens beside it.
 *
 * <p>The card's height follows its content. Two of its lines are conditional — being a legal target,
 * being held — so a fixed height either leaves a band of empty panel most of the time or clips the
 * lines that only appear when something has gone badly wrong for the player.
 */
public final class PlayerCardPanel {

    private static final int CARD_W = 108;
    /** Pitch between the title and the first value line, and between value lines after that. */
    private static final int TITLE_PITCH = 12;
    private static final int LINE_PITCH = 11;
    private static final int PAD = 6;
    /** Amber, the colour a sentence has been drawn in since the HUD gained one. */
    private static final int JAIL = 0xFFB060;

    private PlayerCardPanel() {
    }

    /** The card panel, rendered just to the left of the inventory GUI. */
    public static void renderCard(GuiGraphics g, InventoryScreen screen) {
        Font font = Minecraft.getInstance().font;
        Band band = ClientSelfData.band();
        boolean legalTarget = ClientSelfData.legalTarget();
        boolean captive = ClientCaptiveData.captive();

        // Title, Karma, Standing, Heat, Wanted, Jail, then the two that only sometimes apply.
        int lines = 6 + (legalTarget ? 1 : 0) + (captive ? 1 : 0);
        int cardHeight = PAD + TITLE_PITCH + (lines - 1) * LINE_PITCH + PAD - 2;

        int x = Math.max(2, screen.getGuiLeft() - CARD_W - 4);
        int y = screen.getGuiTop();
        CrimeSprites.panel(g, x, y, CARD_W, cardHeight);

        int tx = x + PAD;
        int ty = y + PAD;

        g.drawString(font, Component.translatable("mcacrime.card.title"), tx, ty,
                PanelColours.TEXT, false);
        ty += TITLE_PITCH;
        g.drawString(font, Component.translatable("mcacrime.card.karma", ClientSelfData.karma()), tx, ty,
                PanelColours.TEXT_MUTED, false);
        ty += LINE_PITCH;

        Component bandName = Component.translatable("mcacrime.band." + band.lower());
        // The band reads twice: as a word, and as the chip that colours it. The chip keeps the bright
        // value the darkened text cannot, and is what the eye finds first.
        CrimeSprites.chip(g, x + CARD_W - PAD - CrimeSprites.CHIP, ty - 1, NameColors.rgb(band), true);
        g.drawString(font, Component.translatable("mcacrime.card.band", bandName), tx, ty,
                PanelColours.onPanel(NameColors.rgb(band)), false);
        ty += LINE_PITCH;

        g.drawString(font, Component.translatable("mcacrime.card.heat", ClientSelfData.heat()), tx, ty,
                PanelColours.TEXT_MUTED, false);
        ty += LINE_PITCH;

        boolean wanted = ClientSelfData.wanted();
        Component status = wanted
                ? Component.translatable("mcacrime.card.wanted")
                : Component.translatable("mcacrime.card.not_wanted");
        g.drawString(font, status, tx, ty,
                wanted ? PanelColours.onPanel(NameColors.RED_RGB) : PanelColours.TEXT_DISABLED, false);
        ty += LINE_PITCH;

        long jailTicks = ClientSelfData.jailRemainingTicks();
        // Nested rather than folded into mcacrime.card.jail, so the free branch stays "Jail: --" instead
        // of reading "Jail: -- remaining".
        Component jail = jailTicks > 0
                ? Component.translatable("mcacrime.card.jail", Component.translatable(
                        "mcacrime.card.jail.remaining", TickFormat.compact(jailTicks)))
                : Component.translatable("mcacrime.card.jail", Component.translatable("mcacrime.card.jail.free"));
        g.drawString(font, jail, tx, ty,
                jailTicks > 0 ? PanelColours.onPanel(JAIL) : PanelColours.TEXT_DISABLED, false);

        if (legalTarget) {
            ty += LINE_PITCH;
            g.drawString(font, Component.translatable("mcacrime.card.legaltarget"), tx, ty,
                    PanelColours.onPanel(NameColors.RED_RGB), false);
        }
        if (captive) {
            ty += LINE_PITCH;
            String captor = ClientCaptiveData.captor();
            Component held = ClientCaptiveData.lawful()
                    ? Component.translatable("mcacrime.card.imprisoned")
                    : Component.translatable("mcacrime.card.captive", captor.isEmpty() ? "?" : captor);
            g.drawString(font, held, tx, ty, PanelColours.onPanel(NameColors.RED_RGB), false);
        }
    }
}
