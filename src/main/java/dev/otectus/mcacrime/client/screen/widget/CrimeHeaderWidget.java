package dev.otectus.mcacrime.client.screen.widget;

import dev.otectus.mcacrime.client.screen.CrimeSprites;
import dev.otectus.mcacrime.client.screen.PanelColours;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;

/**
 * A category heading inside a list of rows.
 *
 * <p>It is a widget only because the list it sits in is made of widgets; it is not interactive, and
 * the single line in its constructor that says so does more work than the rest of the class.
 * Setting {@code active = false} makes {@code AbstractWidget.nextFocusPath} return null, so Tab
 * steps over the heading to the next real row, and makes {@code Screen}'s narration filter skip it,
 * so a screen reader does not announce "Coerce" as something the player could press.
 *
 * <p>The heading still narrates as a title when the row beneath it is reached, which is the
 * behaviour a sighted player gets from seeing it above the row.
 */
public final class CrimeHeaderWidget extends AbstractWidget {

    public CrimeHeaderWidget(Component label) {
        super(0, 0, 0, 0, label);
        this.active = false;
    }

    @Override
    protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        Font font = Minecraft.getInstance().font;
        int textY = getY() + (getHeight() - font.lineHeight) / 2;
        graphics.drawString(font, getMessage().copy().withStyle(style -> style.withBold(true)),
                getX(), textY, PanelColours.TEXT_MUTED, false);

        // A rule under the heading rather than a filled bar behind it: on a light panel a filled
        // heading competes with the rows it is supposed to be organising.
        int ruleY = getY() + getHeight() - 1;
        CrimeSprites.rule(graphics, getX(), ruleY, getWidth(), 0x40000000 | PanelColours.TEXT_MUTED);
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {
        output.add(NarratedElementType.TITLE, getMessage());
    }
}
