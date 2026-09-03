package dev.otectus.mcacrime.client;

import dev.otectus.mcacrime.client.screen.CrimeSprites;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;

/**
 * The small button inside the inventory that opens the reputation card.
 *
 * <p>It used to be a drawn square with a hand-written {@code within()} hit test and a cancelled
 * {@code MouseButtonPressed.Pre} event. That worked, but it was invisible to everything except a
 * mouse: no Tab focus, no narration, no click sound, and a permanent risk that cancelling the event
 * ate a click meant for an inventory slot. As a real widget handed to Forge through
 * {@code ScreenEvent.Init.Post#addListener}, all of that is vanilla's problem instead of ours —
 * including the click routing, since {@code AbstractContainerScreen.mouseClicked} offers the event
 * to its children before it looks at any slot.
 */
final class PlayerCardButton extends AbstractButton {

    /** Matches the sprite. The old drawn square was 11px; the texture is 12. */
    static final int SIZE = 12;

    private final AbstractContainerScreen<?> screen;
    private final int offsetX;
    private final int offsetY;

    PlayerCardButton(AbstractContainerScreen<?> screen, int offsetX, int offsetY) {
        super(0, 0, SIZE, SIZE, Component.translatable("mcacrime.card.button.tooltip"));
        this.screen = screen;
        this.offsetX = offsetX;
        this.offsetY = offsetY;
        setTooltip(Tooltip.create(Component.translatable("mcacrime.card.button.tooltip")));
    }

    @Override
    protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // Positioned every frame rather than once at init. Opening the recipe book slides the whole
        // inventory sideways by changing leftPos without re-running init(), so a position captured
        // when the screen was built would leave the button stranded beside the GUI it belongs to.
        setX(screen.getGuiLeft() + offsetX);
        setY(screen.getGuiTop() + offsetY);

        CrimeSprites.cardButton(graphics, getX(), getY(), isHovered(), PlayerCardScreenHooks.isOpen());

        // The band colour on the button itself, so a player's standing is legible without opening
        // the card at all.
        int inset = (SIZE - CrimeSprites.CHIP) / 2;
        CrimeSprites.chip(graphics, getX() + inset, getY() + inset,
                NameColors.rgb(ClientSelfData.band()), true);
    }

    @Override
    public void onPress() {
        PlayerCardScreenHooks.toggle();
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {
        defaultButtonNarrationText(output);
    }
}
