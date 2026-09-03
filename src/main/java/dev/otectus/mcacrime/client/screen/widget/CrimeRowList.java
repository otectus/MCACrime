package dev.otectus.mcacrime.client.screen.widget;

import dev.otectus.mcacrime.client.screen.CrimeSprites;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.ContainerObjectSelectionList;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.narration.NarratableEntry;
import net.minecraft.util.Mth;

import java.util.List;

/**
 * The scrolling list the mod's rows live in, wearing the mod's chrome instead of vanilla's.
 *
 * <p>The screens used to roll their own: an {@code int scroll} field, a {@code mouseScrolled}
 * override, a {@code (mouseY - listTop + scroll) / ROW_H} hit test, a two-rectangle scrollbar, and
 * a {@code enableScissor} pair around the lot. Vanilla's list already does all of that, and does
 * the parts nobody hand-writes: thumb dragging at the right non-linear speed, {@code ensureVisible}
 * when focus moves off-screen, per-entry hover tracking, and Tab navigation into the rows.
 *
 * <p>{@link ContainerObjectSelectionList} specifically, rather than {@code ObjectSelectionList},
 * because only its entries expose real {@link AbstractWidget} children. That is the difference
 * between a row that can carry a tooltip, take focus and play a click, and a row that merely draws.
 *
 * <p>What is left to do here is stop it looking like the world-selection screen. Vanilla draws four
 * decorations, and each is suppressed differently:
 *
 * <ul>
 *   <li>the full-width menu-list body — {@link #renderListBackground} is overridden to draw the
 *       mod's well instead;
 *   <li>the header and footer separator strips above and below — {@link #renderListSeparators} is
 *       overridden to a no-op;
 *   <li>the white-on-black selection rectangle — already gone, because
 *       {@code ContainerObjectSelectionList} hardcodes {@code isSelectedItem} to false;
 *   <li>the scrollbar — <em>not</em> suppressible. It is drawn inline in {@code render()}, so it is
 *       overdrawn in {@link #renderDecorations} instead, which runs last.
 * </ul>
 */
public class CrimeRowList extends ContainerObjectSelectionList<CrimeRowList.Row> {

    /**
     * One row: a single widget, re-placed by the list every frame.
     *
     * <p>The re-placement is not cosmetic. {@code AbstractWidget.clicked} hit-tests against the
     * widget's own coordinates, and the list moves rows as it scrolls — so a widget left where it
     * was constructed stays clickable at a position it is no longer drawn at, which looks like the
     * screen ignoring clicks at random.
     */
    public static final class Row extends ContainerObjectSelectionList.Entry<Row> {

        private final AbstractWidget widget;

        Row(AbstractWidget widget) {
            this.widget = widget;
        }

        @Override
        public List<? extends GuiEventListener> children() {
            return List.of(widget);
        }

        @Override
        public List<? extends NarratableEntry> narratables() {
            return List.of(widget);
        }

        @Override
        public void render(GuiGraphics graphics, int index, int top, int left, int width, int height,
                           int mouseX, int mouseY, boolean hovered, float partialTick) {
            widget.setX(left);
            widget.setY(top);
            widget.setWidth(width);
            widget.setHeight(height);
            widget.render(graphics, mouseX, mouseY, partialTick);
        }
    }

    private final int scrollbarX;
    private final int rowWidth;

    /**
     * @param left  the left edge of the list area, in screen space
     * @param top   the top edge; rows begin four pixels below it, as vanilla's do
     * @param bottom the bottom edge
     * @param width the full width of the list area, scrollbar included
     */
    public CrimeRowList(Minecraft minecraft, int left, int top, int bottom, int width, int itemHeight) {
        super(minecraft, width, bottom - top, top, itemHeight);
        setX(left);
        this.scrollbarX = scrollbarX(left, width);
        this.rowWidth = rowWidth(width);
    }

    /**
     * How wide a row may be inside a list of this width.
     *
     * <p>Twice the scrollbar's width is subtracted, not once. {@code getEntryAtPosition} is final: it
     * centres the clickable band on the list and then refuses any click at or beyond the scrollbar.
     * A row is therefore only fully clickable if it clears the scrollbar on the right <em>and</em>
     * stays centred, which costs the same width again on the left. Subtracting once looks right and
     * leaves a dead strip down the right-hand edge of every row — clicks that land on a row and do
     * nothing, which reads as the screen being broken rather than as a layout mistake.
     *
     * <p>Static and Minecraft-free so {@code CrimeRowListGeometryTest} can check the arithmetic
     * against the real panel widths at build time, rather than a player finding it.
     */
    public static int rowWidth(int width) {
        return width - 2 * CrimeSprites.SCROLLBAR_W - 2;
    }

    /** Where the scrollbar's left edge sits, and therefore where clicks stop reaching rows. */
    public static int scrollbarX(int left, int width) {
        return left + width - CrimeSprites.SCROLLBAR_W;
    }

    /** The right edge of a drawn row, which must not reach {@link #scrollbarX}. */
    public static int rowRight(int left, int width) {
        return left + width / 2 - rowWidth(width) / 2 + rowWidth(width);
    }

    @Override
    public int getRowWidth() {
        return rowWidth;
    }

    /**
     * Vanilla offsets the drawn row two pixels right of the band it hit-tests. Dropping that offset
     * makes what is drawn and what is clickable the same rectangle, which is the only way the two
     * cannot drift apart.
     */
    @Override
    public int getRowLeft() {
        return getX() + getWidth() / 2 - getRowWidth() / 2;
    }

    @Override
    protected int getScrollbarPosition() {
        return scrollbarX;
    }

    /** The sunken area the rows sit in — drawn where vanilla would have blitted its menu background. */
    @Override
    protected void renderListBackground(GuiGraphics graphics) {
        CrimeSprites.well(graphics, getX(), getY(), getWidth(), getHeight());
    }

    /**
     * Suppresses vanilla's header and footer separator strips.
     *
     * <p>They are the 1.21.1 replacement for the dirt strips {@code setRenderTopAndBottom(false)}
     * used to turn off, and they read as the world-selection screen rather than as this mod's panel.
     */
    @Override
    protected void renderListSeparators(GuiGraphics graphics) {
    }

    /**
     * Draws the mod's scrollbar over vanilla's.
     *
     * <p>Overdrawn rather than prevented, because the only way to prevent it is to reimplement
     * {@code render()} — and {@code render()} maintains the private {@code hovered} field that every
     * row's hover state and the list's narration priority both read. Losing that to tidy away six
     * pixels would be a bad trade. The geometry below is vanilla's own, so the two register exactly.
     */
    @Override
    protected void renderDecorations(GuiGraphics graphics, int mouseX, int mouseY) {
        int maxScroll = getMaxScroll();
        if (maxScroll <= 0) {
            return;
        }
        int span = getHeight();
        int thumbHeight = Mth.clamp(span * span / getMaxPosition(), 32, span - 8);
        int thumbY = Math.max(getY(), (int) getScrollAmount() * (span - thumbHeight) / maxScroll + getY());

        CrimeSprites.scrollTrack(graphics, scrollbarX, getY(), span);
        CrimeSprites.scrollThumb(graphics, scrollbarX, thumbY, thumbHeight);
    }

    /** Adds a row. Public because {@code addEntry} is not, and screens build their own lists. */
    public void addRow(AbstractWidget widget) {
        addEntry(new Row(widget));
    }

    /** Empties the list, for a screen whose data arrived after it was first laid out. */
    public void clearRows() {
        clearEntries();
        setScrollAmount(0.0D);
    }
}
