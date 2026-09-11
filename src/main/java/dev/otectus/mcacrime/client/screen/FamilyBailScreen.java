package dev.otectus.mcacrime.client.screen;

import dev.otectus.mcacrime.action.CrimeActionIds;
import dev.otectus.mcacrime.network.BailQuoteS2CPacket;
import dev.otectus.mcacrime.network.CrimeNetwork;
import dev.otectus.mcacrime.network.StartActionC2SPacket;
import dev.otectus.mcacrime.util.TickFormat;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.UUID;

/**
 * What bailing out a relative costs, and what it buys.
 *
 * <p>{@link CrimeConfirmationScreen} could not serve here and that is why this exists: its body is a
 * fixed "are you sure" line about an action and a target, with nowhere to put a price, an offence, or
 * how long the sentence still has to run. Asking somebody to spend emeralds without telling them how
 * many is not a confirmation.
 *
 * <p>The Pay button sends the ordinary {@link StartActionC2SPacket} through the same menu session the
 * quote was issued against, with a fresh nonce like every other action. Nothing on this screen is
 * trusted: the server re-prices the sentence and re-checks custody before a single emerald moves, so a
 * stale quote left open on screen can cost the player nothing it did not say it would.
 */
public final class FamilyBailScreen extends Screen {

    private static final int PANEL_W = 248;
    private static final int PANEL_H = 136;

    private final Screen parent;
    private final BailQuoteS2CPacket quote;

    public FamilyBailScreen(Screen parent, BailQuoteS2CPacket quote) {
        super(Component.translatable("gui.mcacrime.bail.title"));
        this.parent = parent;
        this.quote = quote;
    }

    @Override
    protected void init() {
        int left = (width - PANEL_W) / 2;
        int top = (height - PANEL_H) / 2;
        int buttonY = top + PANEL_H - 28;

        addRenderableWidget(Button.builder(
                        Component.translatable("gui.mcacrime.bail.pay", quote.cost()), b -> {
                            send();
                            if (minecraft != null) minecraft.setScreen(null);
                        })
                .bounds(left + 10, buttonY, (PANEL_W - 30) / 2, 20).build());

        addRenderableWidget(Button.builder(Component.translatable("gui.mcacrime.bail.decline"), b -> onClose())
                .bounds(left + PANEL_W / 2 + 5, buttonY, (PANEL_W - 30) / 2, 20).build());
    }

    private void send() {
        CrimeNetwork.sendToServer(new StartActionC2SPacket(UUID.randomUUID(), quote.menuId(),
                quote.menuRevision(), CrimeActionIds.PAY_BAIL, quote.targetId()));
    }

    /** Never pauses the integrated server: the sentence this is pricing is still running. */
    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void onClose() {
        if (minecraft != null) minecraft.setScreen(parent);
    }

    /**
     * The panel and the four lines that justify the price.
     *
     * <p>Drawn from {@code renderBackground} for the reason {@code GuardChallengeScreen} draws its
     * chrome there: 1.21.1's {@code Screen.render} calls this hook itself, so anything painted in
     * {@code render} before {@code super.render} is covered by the blur and the menu background.
     * Never call {@code super.render} here: it reenters this hook and overflows the stack.
     */
    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.renderBackground(graphics, mouseX, mouseY, partialTick);
        int left = (width - PANEL_W) / 2;
        int top = (height - PANEL_H) / 2;
        CrimeSprites.panel(graphics, left, top, PANEL_W, PANEL_H);

        graphics.drawString(font, title, left + 8, top + 8, PanelColours.TEXT, false);
        line(graphics, left, top + 26, "gui.mcacrime.bail.relative", Component.literal(quote.relativeName()));
        line(graphics, left, top + 40, "gui.mcacrime.bail.offence",
                Component.translatable(quote.offenceKey()));
        line(graphics, left, top + 54, "gui.mcacrime.bail.cost", Component.literal(String.valueOf(quote.cost())));
        line(graphics, left, top + 68, "gui.mcacrime.bail.remaining",
                Component.literal(TickFormat.compact(quote.remainingTicks())));
        graphics.drawString(font, Component.translatable(quote.releaseConditionKey()), left + 8, top + 86,
                PanelColours.TEXT_MUTED, false);
    }

    private void line(GuiGraphics graphics, int left, int y, String labelKey, Component value) {
        graphics.drawString(font, Component.translatable(labelKey), left + 8, y, PanelColours.TEXT_MUTED, false);
        graphics.drawString(font, value, left + 100, y, PanelColours.TEXT, false);
    }
}
