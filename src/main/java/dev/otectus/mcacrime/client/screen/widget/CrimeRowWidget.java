package dev.otectus.mcacrime.client.screen.widget;

import dev.otectus.mcacrime.client.screen.CrimeSprites;
import dev.otectus.mcacrime.client.screen.PanelColours;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;

import javax.annotation.Nullable;

/**
 * One row of a mod list: a coloured chip, a label, and something short on the right.
 *
 * <p>These rows used to be rectangles drawn by the screen and hit-tested with
 * {@code (mouseY - listTop + scroll) / ROW_H}. That worked for a mouse and for nothing else. The
 * spec is explicit about why that is not good enough — "use a real widget, not a render-only
 * hotspot; it must support keyboard focus, narration, GUI scale, localization, and controller
 * mods" — and every one of those follows from extending a real button rather than from any code
 * written here.
 *
 * <p>It extends {@link AbstractButton} rather than {@code AbstractWidget} because
 * {@code AbstractButton} already routes a click to {@link #onPress()}, already treats Enter and
 * Space as a press, and already plays the vanilla click sound. Only the drawing is ours.
 *
 * <p>A row with no action — a dossier entry, an empty-state line — passes a {@code null} press
 * handler. It still renders, still narrates, and still shows its tooltip; it simply does not claim
 * to be pressable, which matters to a screen reader far more than it does to a mouse.
 */
public final class CrimeRowWidget extends AbstractButton {

    private static final int PADDING = 4;

    private final int chipRgb;
    private final Component trailing;
    private final int trailingRgb;
    @Nullable
    private final Runnable onPress;

    public CrimeRowWidget(Component label, int chipRgb, Component trailing, int trailingRgb,
                          boolean enabled, @Nullable Runnable onPress) {
        super(0, 0, 0, 0, label);
        this.chipRgb = chipRgb;
        this.trailing = trailing;
        this.trailingRgb = trailingRgb;
        this.onPress = onPress;
        this.active = enabled;
    }

    @Override
    protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        Font font = Minecraft.getInstance().font;

        // Focus outranks hover: a player driving the screen from the keyboard needs to see where
        // they are even while the mouse happens to rest somewhere else.
        CrimeSprites.RowState state = !active ? CrimeSprites.RowState.DISABLED
                : isFocused() ? CrimeSprites.RowState.FOCUS
                : isHovered() ? CrimeSprites.RowState.HOVER
                : CrimeSprites.RowState.IDLE;
        CrimeSprites.row(graphics, getX(), getY(), getWidth(), getHeight(), state);

        CrimeSprites.chip(graphics, getX() + PADDING, getY() + (getHeight() - CrimeSprites.CHIP) / 2,
                chipRgb, active);

        int textY = getY() + (getHeight() - font.lineHeight) / 2 + 1;
        int textLeft = getX() + PADDING + CrimeSprites.CHIP + PADDING;
        int trailingWidth = trailing.getString().isEmpty() ? 0 : font.width(trailing) + PADDING;

        // Clipped rather than truncated. A label is a translation, and the German or Russian one is
        // routinely half again as long as the English it was laid out against; letting it run under
        // the duration on the right is the failure this prevents.
        graphics.enableScissor(textLeft, getY(), getX() + getWidth() - PADDING - trailingWidth,
                getY() + getHeight());
        graphics.drawString(font, getMessage(), textLeft, textY,
                active ? PanelColours.TEXT : PanelColours.TEXT_DISABLED, false);
        graphics.disableScissor();

        if (trailingWidth > 0) {
            graphics.drawString(font, trailing,
                    getX() + getWidth() - PADDING - font.width(trailing), textY,
                    active ? trailingRgb : PanelColours.TEXT_DISABLED, false);
        }
    }

    @Override
    public void onPress() {
        if (onPress != null) {
            onPress.run();
        }
    }

    @Override
    protected boolean isValidClickButton(int button) {
        return onPress != null && button == 0;
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {
        if (onPress != null) {
            defaultButtonNarrationText(output);
        } else {
            // Announced as a line of text, not as "press to activate" — because it cannot be.
            output.add(NarratedElementType.TITLE, getMessage());
        }
    }
}
