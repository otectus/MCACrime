package dev.otectus.mcacrime.client.screen;

import dev.otectus.mcacrime.client.ClientChallengeData;
import dev.otectus.mcacrime.enforcement.ChallengeResponse;
import dev.otectus.mcacrime.network.CrimeNetwork;
import dev.otectus.mcacrime.network.GuardChallengeResponseC2SPacket;
import dev.otectus.mcacrime.util.TickFormat;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;

import java.util.UUID;

/**
 * The screen a guard's challenge opens (spec §13.2).
 *
 * <p>It states the jurisdiction and how many charges are outstanding, and offers only the responses
 * that actually apply — "Pay the fine" is absent, not greyed, when the charges are not finable, because
 * a disabled button on a timer reads as something you failed to reach in time.
 *
 * <p>Closing the screen is not refusing. The window keeps running on the server and expiry is what
 * counts as refusal, so a player who hits Escape to look around has not thereby answered; that is why
 * there is no "Back" button pretending to be neutral, and why the countdown is the most prominent
 * thing on the panel.
 *
 * <p>Every position comes from {@link ChallengePanelLayout} rather than from constants here. The
 * countdown used to be drawn at a y computed from the panel height, which put it inside the last
 * button, and before {@code super.render()}, which let the button paint over it — so the timer this
 * class describes as its whole point was invisible whenever all four responses were offered.
 */
public final class GuardChallengeScreen extends Screen {

    /** The challenge's own colour: this screen is a timer, and the timer is the point. */
    private static final int URGENT = 0xFF5555;
    /** Below this many ticks the countdown turns red. */
    private static final long URGENT_TICKS = 60L;

    private ChallengePanelLayout layout = ChallengePanelLayout.of(4);
    private int panelLeft;
    private int panelTop;

    public GuardChallengeScreen() {
        super(Component.translatable("gui.mcacrime.challenge.title"));
    }

    @Override
    protected void init() {
        var challenge = ClientChallengeData.current();
        if (challenge == null) {
            onClose();
            return;
        }
        // Three responses when the charges cannot be settled with money, four when they can. The panel
        // is sized to what it actually shows rather than to the worst case, so the three-button form
        // does not carry an empty strip where a button would have been.
        layout = ChallengePanelLayout.of(challenge.canPay() ? 4 : 3);
        panelLeft = (width - layout.panelWidth()) / 2;
        panelTop = (height - layout.panelHeight()) / 2;

        UUID encounter = challenge.encounterId();
        int index = 0;
        addResponse(encounter, ChallengeResponse.SURRENDER,
                Component.translatable(ChallengeResponse.SURRENDER.labelKey()), index++);
        if (challenge.canPay()) {
            addResponse(encounter, ChallengeResponse.PAY_FINE,
                    Component.translatable("gui.mcacrime.challenge.pay_fine.amount", challenge.assessedFine()),
                    index++);
        }
        addResponse(encounter, ChallengeResponse.ASK_CHARGES,
                Component.translatable(ChallengeResponse.ASK_CHARGES.labelKey()), index++);
        addResponse(encounter, ChallengeResponse.REFUSE,
                Component.translatable(ChallengeResponse.REFUSE.labelKey()), index);
    }

    private void addResponse(UUID encounter, ChallengeResponse response, Component label, int index) {
        addRenderableWidget(Button.builder(label, b -> respond(encounter, response))
                .bounds(panelLeft + layout.contentLeft(), panelTop + layout.buttonY(index),
                        layout.buttonWidth(), layout.buttonHeight())
                .build());
    }

    /**
     * Sends the answer. Asking for the charges deliberately leaves the screen open, because the whole
     * point of asking is to decide afterwards.
     */
    private void respond(UUID encounter, ChallengeResponse response) {
        CrimeNetwork.CHANNEL.sendToServer(new GuardChallengeResponseC2SPacket(encounter, response));
        if (response.closesEncounter() && minecraft != null) {
            minecraft.setScreen(null);
        }
    }

    /**
     * Never pauses. A challenge is a timer, and a screen that stopped the integrated server's clock
     * would let a singleplayer player think about surrendering indefinitely.
     */
    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        if (!ClientChallengeData.active() && minecraft != null) {
            // The server closed the encounter under us — paid, surrendered, or the guard gave up.
            minecraft.setScreen(null);
            return;
        }
        renderBackground(graphics);
        var challenge = ClientChallengeData.current();
        CrimeSprites.panel(graphics, panelLeft, panelTop, layout.panelWidth(), layout.panelHeight());

        // The panel used to carry its urgency as a red bar along its top edge. On a light panel that
        // reads as decoration, so the red moves to a chip beside the title, where it reads as a state.
        CrimeSprites.chip(graphics, panelLeft + layout.panelWidth() - 18, panelTop + 7, URGENT, true);

        graphics.drawString(font, title, panelLeft + 10, panelTop + layout.titleY(),
                PanelColours.TEXT, false);
        if (challenge != null) {
            graphics.drawString(font, challenge.guardName(), panelLeft + 10,
                    panelTop + layout.guardNameY(), PanelColours.TEXT_MUTED, false);
            graphics.drawString(font,
                    Component.translatable("gui.mcacrime.challenge.charges", challenge.chargeCount()),
                    panelLeft + 10, panelTop + layout.chargesY(), PanelColours.TEXT, false);
        }

        super.render(graphics, mouseX, mouseY, partialTick);

        // Drawn after the widgets, not before. The countdown used to sit under the Refuse button in
        // both senses — the wrong y, and the wrong draw order — so the one number the player needs in
        // order to decide was the one thing they could not see.
        drawStatusLine(graphics, challenge);
    }

    /** The jurisdiction on the left and the countdown on the right, sharing one line above the buttons. */
    private void drawStatusLine(GuiGraphics graphics, dev.otectus.mcacrime.network.GuardChallengeS2CPacket challenge) {
        long remaining = ClientChallengeData.remainingTicks();
        Component countdown = Component.translatable("gui.mcacrime.challenge.remaining",
                TickFormat.clock(remaining));
        int countdownWidth = font.width(countdown);
        int y = panelTop + layout.statusY();
        graphics.drawString(font, countdown, panelLeft + layout.panelWidth() - 10 - countdownWidth, y,
                remaining < URGENT_TICKS ? PanelColours.onPanel(URGENT) : PanelColours.TEXT_MUTED, false);

        if (challenge == null) {
            return;
        }
        Component jurisdiction = Component.translatable("gui.mcacrime.challenge.jurisdiction",
                challenge.jurisdiction());
        // The two share a line, so the village name yields to the countdown rather than running under
        // it: a truncated place name is readable, two overlapping strings are not.
        int available = layout.panelWidth() - 20 - countdownWidth - 6;
        graphics.drawString(font,
                Language.getInstance().getVisualOrder(font.substrByWidth(jurisdiction, available)),
                panelLeft + 10, y, PanelColours.TEXT_MUTED, false);
    }
}
