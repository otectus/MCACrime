package dev.otectus.mcacrime.client.screen;

import dev.otectus.mcacrime.action.ActionLegality;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * The "are you sure" step in front of a hostile action (spec §8.4).
 *
 * <p>This is presentation only. Suppressing it with the client option changes what the player is
 * asked, never what the server allows — every action is re-validated on arrival regardless of whether
 * this screen was shown, so a client that skips it gains nothing but speed.
 */
public final class CrimeConfirmationScreen extends Screen {

    private static final int PANEL_W = 240;
    private static final int PANEL_H = 108;

    private final Screen parent;
    private final Component actionName;
    private final Component targetName;
    private final ActionLegality legality;
    private final Runnable onConfirm;

    public CrimeConfirmationScreen(Screen parent, Component actionName, Component targetName,
                                   ActionLegality legality, Runnable onConfirm) {
        super(Component.translatable("gui.mcacrime.confirm.title"));
        this.parent = parent;
        this.actionName = actionName;
        this.targetName = targetName;
        this.legality = legality;
        this.onConfirm = onConfirm;
    }

    @Override
    protected void init() {
        int left = (width - PANEL_W) / 2;
        int top = (height - PANEL_H) / 2;
        int buttonY = top + PANEL_H - 28;

        addRenderableWidget(Button.builder(Component.translatable("gui.mcacrime.confirm.yes"), b -> {
            onConfirm.run();
            if (minecraft != null) minecraft.setScreen(null);
        }).bounds(left + 10, buttonY, (PANEL_W - 30) / 2, 20).build());

        addRenderableWidget(Button.builder(Component.translatable("gui.mcacrime.confirm.no"), b -> onClose())
                .bounds(left + PANEL_W / 2 + 5, buttonY, (PANEL_W - 30) / 2, 20).build());
    }

    /**
     * Never pauses the integrated server. A confirmation dialog that froze singleplayer would stop the
     * very clocks the action is racing — guard challenge windows, capture channels, captivity caps.
     */
    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void onClose() {
        if (minecraft != null) minecraft.setScreen(parent);
    }

    /**
     * The panel, its two lines of text and the legality chip.
     *
     * <p>Drawn from {@code renderBackground} rather than from {@code render}: 1.21.1's
     * {@code Screen.render} calls {@code renderBackground} itself, so chrome drawn in {@code render}
     * before {@code super.render} would be painted over by the blur and the menu background.
     */
    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.renderBackground(graphics, mouseX, mouseY, partialTick);
        int left = (width - PANEL_W) / 2;
        int top = (height - PANEL_H) / 2;
        CrimeSprites.panel(graphics, left, top, PANEL_W, PANEL_H);

        // Centred by hand rather than with drawCenteredString, which always draws a shadow: dark
        // text with a dark shadow on a light panel is mud.
        centred(graphics, title, top + 10, PanelColours.TEXT);
        centred(graphics, actionName, top + 30, PanelColours.onPanel(legality.rgb()));
        centred(graphics, Component.translatable("gui.mcacrime.confirm.body", targetName),
                top + 46, PanelColours.TEXT_MUTED);

        // The legality reads twice: as a word, and as the chip beside it. The chip keeps the bright
        // colour the word cannot, and is what a player scanning the dialog actually sees first.
        Component label = Component.translatable(legality.labelKey());
        int labelWidth = font.width(label) + CrimeSprites.CHIP + 4;
        int labelLeft = width / 2 - labelWidth / 2;
        CrimeSprites.chip(graphics, labelLeft, top + 60, legality.rgb(), true);
        graphics.drawString(font, label, labelLeft + CrimeSprites.CHIP + 4, top + 60,
                PanelColours.onPanel(legality.rgb()), false);
    }

    private void centred(GuiGraphics graphics, Component text, int y, int colour) {
        graphics.drawString(font, text, width / 2 - font.width(text) / 2, y, colour, false);
    }
}
