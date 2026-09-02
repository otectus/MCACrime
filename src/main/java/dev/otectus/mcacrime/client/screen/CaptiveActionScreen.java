package dev.otectus.mcacrime.client.screen;

import dev.otectus.mcacrime.client.ClientActionData;
import dev.otectus.mcacrime.client.ClientCaptiveData;
import dev.otectus.mcacrime.network.ActionMenuS2CPacket;
import dev.otectus.mcacrime.util.TickFormat;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

/**
 * The panel a held player gets (spec §14.3).
 *
 * <p>It is the action screen with a status header rather than a separate screen with its own row
 * logic, and that is deliberate: a captive's options are ordinary actions with ordinary server-side
 * validation, and giving them a bespoke list would be a second place for the rules to be written down
 * and drift.
 *
 * <p>What the header adds is the three facts a captive actually needs and previously had to piece
 * together from chat scrollback — who is holding them, how long the hard cap has left, and how their
 * escape work is going. The cap especially: it is the guarantee that captivity ends, and a guarantee
 * the player cannot see is not one they can rely on.
 */
public final class CaptiveActionScreen extends CrimeInteractionScreen {

    private static final int HEADER_LINES = 3;

    /** Being held unlawfully is the one line here a captive must not skim past. */
    private static final int UNLAWFUL = 0xFF5555;

    public CaptiveActionScreen(ActionMenuS2CPacket menu, net.minecraft.client.gui.screens.Screen parent) {
        super(menu, parent, Component.translatable("gui.mcacrime.captive.title"));
    }

    @Override
    protected int extraHeaderHeight() {
        return HEADER_LINES * 11 + 4;
    }

    @Override
    protected void renderExtraHeader(GuiGraphics graphics, int left, int top, int width) {
        int y = top;

        String captor = ClientCaptiveData.captor();
        graphics.drawString(font, captor.isEmpty()
                        ? Component.translatable("gui.mcacrime.captive.held_by_unknown")
                        : Component.translatable("gui.mcacrime.captive.held_by", captor),
                left, y, PanelColours.TEXT, false);
        y += 11;

        graphics.drawString(font, Component.translatable(
                        ClientCaptiveData.lawful() ? "gui.mcacrime.captive.lawful" : "gui.mcacrime.captive.unlawful"),
                left, y, ClientCaptiveData.lawful()
                        ? PanelColours.TEXT_MUTED : PanelColours.onPanel(UNLAWFUL), false);
        y += 11;

        long cap = ClientCaptiveData.capRemainingTicks();
        if (cap > 0L) {
            graphics.drawString(font,
                    Component.translatable("gui.mcacrime.captive.cap", TickFormat.clock(cap)),
                    left, y, PanelColours.TEXT_MUTED, false);
        } else {
            graphics.drawString(font, Component.translatable("gui.mcacrime.captive.cap_none"),
                    left, y, PanelColours.TEXT_DISABLED, false);
        }

        // The escape bar, when one is running, is drawn by the HUD overlay rather than duplicated here:
        // a captive working at their restraints is usually looking at the world, not at this panel.
        if (ClientActionData.channelling()) {
            Component progress = Component.translatable("gui.mcacrime.captive.working");
            graphics.drawString(font, progress, left + width - font.width(progress), y,
                    PanelColours.TEXT_MUTED, false);
        }
    }
}
