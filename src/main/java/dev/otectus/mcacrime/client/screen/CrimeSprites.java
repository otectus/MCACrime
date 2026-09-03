package dev.otectus.mcacrime.client.screen;

import com.mojang.blaze3d.systems.RenderSystem;
import dev.otectus.mcacrime.McaCrime;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;

/**
 * Every pixel of mod chrome, drawn from one sheet.
 *
 * <p>This is the only class that names the GUI texture and the only class permitted to call
 * {@link GuiGraphics#setColor}. Both restrictions exist for one reason: a screen that draws its own
 * chrome is a screen that drifts, and this mod has already watched that happen once — the inventory
 * card kept private copies of the panel colours and slowly stopped matching the screens beside it.
 * It replaces the drawn panel this mod used to have, so the chrome is now a texture rather than a
 * stack of rectangles and the panels are bevelled the way vanilla's are.
 *
 * <p>Two invariants every method here keeps, because the alternative is a bug nobody can see in a
 * screenshot:
 *
 * <ul>
 *   <li><b>Blending is enabled, never assumed.</b> {@code AbstractSelectionList.render} ends with
 *       {@code RenderSystem.disableBlend()}, so anything drawn after a list — a HUD plate, a dimmed
 *       chip — would otherwise lose its alpha depending on nothing but draw order.
 *   <li><b>The shader colour is always restored.</b> {@code setColor} is global: leaving a tint set
 *       would colour the text drawn after it, then the hotbar, then the held item. Only
 *       {@link #chip} sets it, and it clears it on the same straight line.
 * </ul>
 *
 * <p>Depth testing is deliberately <em>not</em> touched. {@code AbstractContainerScreen.render}
 * disables it around its widget pass on purpose; enabling it here would z-fight the inventory the
 * player card is drawn over.
 *
 * <p>The sheet's layout is documented, and checked, in {@code tools/gui/generate_gui_sheet.py}.
 * The UV constants below must match it.
 */
public final class CrimeSprites {

    public static final ResourceLocation SHEET =
            ResourceLocation.fromNamespaceAndPath(McaCrime.MOD_ID, "textures/gui/panel.png");

    /**
     * How a list row is drawn.
     *
     * <p>Presentation only: it deliberately has no {@code labelKey()} and builds no translation key
     * by concatenation. If one is ever added, {@code LangCoverageTest.everyConcatenatedFamilyIsEnumerated}
     * must be told about it, because that test cannot discover such a family on its own.
     */
    public enum RowState {
        IDLE(0),
        HOVER(1),
        DISABLED(2),
        FOCUS(3);

        private final int index;

        RowState(int index) {
            this.index = index;
        }
    }

    /** Side length of a legality/band chip, so callers can centre one without knowing the sheet. */
    public static final int CHIP = 8;

    /** Height of the progress bar, track included. */
    public static final int BAR_H = 8;

    /** Width of the scrollbar, matching vanilla's own six pixels. */
    public static final int SCROLLBAR_W = 6;

    private CrimeSprites() {
    }

    /** The raised body of a screen. */
    public static void panel(GuiGraphics graphics, int x, int y, int width, int height) {
        RenderSystem.enableBlend();
        nineSlice(graphics, x, y, width, height, 4, 32, 32, 0, 0);
    }

    /** The sunken area a list of rows sits in, so the list reads as inset into the panel. */
    public static void well(GuiGraphics graphics, int x, int y, int width, int height) {
        RenderSystem.enableBlend();
        nineSlice(graphics, x, y, width, height, 3, 32, 32, 32, 0);
    }

    /**
     * The translucent plate behind a HUD element.
     *
     * <p>Not the grey panel: a light slab floating over the world is not what vanilla does with
     * information overlaid on gameplay. This is the boss-bar and subtitle idiom instead, and its
     * alpha is baked into the texture — which is exactly why blending must be on.
     */
    public static void hudPlate(GuiGraphics graphics, int x, int y, int width, int height) {
        RenderSystem.enableBlend();
        nineSlice(graphics, x, y, width, height, 3, 32, 32, 64, 0);
    }

    /** A list row in one of its four states. */
    public static void row(GuiGraphics graphics, int x, int y, int width, int height, RowState state) {
        RenderSystem.enableBlend();
        nineSlice(graphics, x, y, width, height, 3, 2, 32, 20, 0, 32 + state.index * 20);
    }

    /** The scrollbar groove. Tiled vertically, so any list height works. */
    public static void scrollTrack(GuiGraphics graphics, int x, int y, int height) {
        RenderSystem.enableBlend();
        repeating(graphics, x, y, SCROLLBAR_W, height, 0, 112, SCROLLBAR_W, 32);
    }

    /** The scrollbar handle. */
    public static void scrollThumb(GuiGraphics graphics, int x, int y, int height) {
        RenderSystem.enableBlend();
        nineSlice(graphics, x, y, SCROLLBAR_W, height, 1, SCROLLBAR_W, 32, 8, 112);
    }

    /**
     * The small square carrying a legality, band or resolution colour.
     *
     * <p>A shape rather than a word, so it survives translation into a language where the word is
     * much longer. It keeps the bright original colour rather than {@link PanelColours#onPanel} —
     * a saturated square inside a black frame reads at any lightness, and darkening it would cost
     * the only thing it is for.
     */
    public static void chip(GuiGraphics graphics, int x, int y, int rgb, boolean enabled) {
        RenderSystem.enableBlend();
        float alpha = enabled ? 1.0F : 0.38F;
        graphics.setColor(((rgb >> 16) & 0xFF) / 255.0F, ((rgb >> 8) & 0xFF) / 255.0F,
                (rgb & 0xFF) / 255.0F, alpha);
        graphics.blit(SHEET, x, y, 16, 112, CHIP, CHIP);
        graphics.setColor(1.0F, 1.0F, 1.0F, 1.0F);
        graphics.blit(SHEET, x, y, 24, 112, CHIP, CHIP);
    }

    /**
     * A progress bar filled to {@code fraction} of its width.
     *
     * <p>The fill is drawn at exactly six pixels tall on purpose: at its own height,
     * {@link #nineSlice} slices horizontally only, so the bar's vertical shading is never tiled
     * and never bands.
     */
    public static void bar(GuiGraphics graphics, int x, int y, int width, float fraction) {
        RenderSystem.enableBlend();
        nineSlice(graphics, x, y, width, BAR_H, 1, 32, 8, 0, 144);
        int filled = Math.round((width - 2) * Math.max(0.0F, Math.min(1.0F, fraction)));
        if (filled > 0) {
            nineSlice(graphics, x + 1, y + 1, filled, 6, 1, 0, 32, 6, 0, 152);
        }
    }

    /** The small square button that opens the player card inside the inventory. */
    public static void cardButton(GuiGraphics graphics, int x, int y, boolean hovered, boolean open) {
        RenderSystem.enableBlend();
        int v = open ? 168 : hovered ? 156 : 144;
        graphics.blit(SHEET, x, y, 32, v, 12, 12);
    }

    // ------------------------------------------------------------------ slicing

    // 1.21.1 removed GuiGraphics.blitNineSliced and blitRepeating for plain textures: what is left
    // slices only sprites out of the GUI atlas, and this mod's chrome is one sheet, not an atlas.
    // The two helpers below are the removed methods' own arithmetic, kept so every UV constant on the
    // sheet — and tools/gui/generate_gui_sheet.py, which produces them — stays valid unchanged.

    /** Nine-slices a region of the sheet with square corners. */
    private static void nineSlice(GuiGraphics graphics, int x, int y, int width, int height,
                                  int slice, int uWidth, int vHeight, int uOffset, int vOffset) {
        nineSlice(graphics, x, y, width, height, slice, slice, uWidth, vHeight, uOffset, vOffset);
    }

    /** Nine-slices a region of the sheet with corners of independent width and height. */
    private static void nineSlice(GuiGraphics graphics, int x, int y, int width, int height,
                                  int cornerWidth, int cornerHeight, int uWidth, int vHeight,
                                  int uOffset, int vOffset) {
        int cw = Math.min(cornerWidth, width / 2);
        int ch = Math.min(cornerHeight, height / 2);
        if (width == uWidth && height == vHeight) {
            graphics.blit(SHEET, x, y, uOffset, vOffset, width, height);
        } else if (height == vHeight) {
            graphics.blit(SHEET, x, y, uOffset, vOffset, cw, height);
            repeating(graphics, x + cw, y, width - 2 * cw, height,
                    uOffset + cw, vOffset, uWidth - 2 * cw, vHeight);
            graphics.blit(SHEET, x + width - cw, y, uOffset + uWidth - cw, vOffset, cw, height);
        } else if (width == uWidth) {
            graphics.blit(SHEET, x, y, uOffset, vOffset, width, ch);
            repeating(graphics, x, y + ch, width, height - 2 * ch,
                    uOffset, vOffset + ch, uWidth, vHeight - 2 * ch);
            graphics.blit(SHEET, x, y + height - ch, uOffset, vOffset + vHeight - ch, width, ch);
        } else {
            graphics.blit(SHEET, x, y, uOffset, vOffset, cw, ch);
            repeating(graphics, x + cw, y, width - 2 * cw, ch,
                    uOffset + cw, vOffset, uWidth - 2 * cw, ch);
            graphics.blit(SHEET, x + width - cw, y, uOffset + uWidth - cw, vOffset, cw, ch);
            graphics.blit(SHEET, x, y + height - ch, uOffset, vOffset + vHeight - ch, cw, ch);
            repeating(graphics, x + cw, y + height - ch, width - 2 * cw, ch,
                    uOffset + cw, vOffset + vHeight - ch, uWidth - 2 * cw, ch);
            graphics.blit(SHEET, x + width - cw, y + height - ch,
                    uOffset + uWidth - cw, vOffset + vHeight - ch, cw, ch);
            repeating(graphics, x, y + ch, cw, height - 2 * ch,
                    uOffset, vOffset + ch, cw, vHeight - 2 * ch);
            repeating(graphics, x + cw, y + ch, width - 2 * cw, height - 2 * ch,
                    uOffset + cw, vOffset + ch, uWidth - 2 * cw, vHeight - 2 * ch);
            repeating(graphics, x + width - cw, y + ch, cw, height - 2 * ch,
                    uOffset + uWidth - cw, vOffset + ch, cw, vHeight - 2 * ch);
        }
    }

    /** Tiles a region of the sheet from its top-left corner, cropping the last row and column. */
    private static void repeating(GuiGraphics graphics, int x, int y, int width, int height,
                                  int uOffset, int vOffset, int sourceWidth, int sourceHeight) {
        if (sourceWidth <= 0 || sourceHeight <= 0) {
            return;
        }
        int drawX = x;
        for (int remainingWidth = width; remainingWidth > 0; remainingWidth -= sourceWidth) {
            int stepWidth = Math.min(remainingWidth, sourceWidth);
            int drawY = y;
            for (int remainingHeight = height; remainingHeight > 0; remainingHeight -= sourceHeight) {
                int stepHeight = Math.min(remainingHeight, sourceHeight);
                graphics.blit(SHEET, drawX, drawY, uOffset, vOffset, stepWidth, stepHeight);
                drawY += stepHeight;
            }
            drawX += stepWidth;
        }
    }

    /**
     * A one-pixel horizontal rule in a given ARGB colour.
     *
     * <p>A fill rather than a sprite, and deliberately so: a texture for a solid one-pixel line
     * would be the same pixels plus a texture bind. It lives here anyway so that the style has one
     * home and a screen still never has to decide what its own chrome looks like.
     */
    public static void rule(GuiGraphics graphics, int x, int y, int width, int argb) {
        graphics.fill(x, y, x + width, y + 1, argb);
    }
}
