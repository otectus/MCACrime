package dev.otectus.mcacrime.client.hud;

/**
 * Where a HUD element sits, and the arithmetic that puts it there.
 *
 * <p>Deliberately free of any Minecraft type so the placement can be unit-tested. Getting an overlay
 * position wrong is not a crash — it is an element drawn half off the screen at one GUI scale and
 * nowhere near the hotbar at another, which is exactly the class of bug that only shows up on
 * somebody else's monitor.
 *
 * <p>Offsets are measured <i>inward from the anchored edge</i>: the same {@code 4, 4} that puts an
 * element four pixels below and right of the top-left corner puts it four pixels above and right of
 * the bottom-left one. A single pair of offset defaults therefore reads sensibly at all eight
 * anchors, instead of only at the two that happen to count from the origin.
 *
 * <p>Bottom anchors additionally carry a fixed clearance for the vanilla HUD band, so choosing a
 * bottom corner does not silently put the element behind the hotbar.
 *
 * <p><b>This class must stay Minecraft-free.</b> It sits in a client package but is named by the
 * client config spec, whose enclosing class is constructed on both sides — so a dedicated server does
 * classload it. Pure Java is fine there; a single client-only import would not be.
 */
public enum HudAnchor {
    TOP_LEFT(0.0F, 0.0F, 0),
    TOP_CENTER(0.5F, 0.0F, 0),
    TOP_RIGHT(1.0F, 0.0F, 0),
    CENTER_LEFT(0.0F, 0.5F, 0),
    CENTER_RIGHT(1.0F, 0.5F, 0),
    BOTTOM_LEFT(0.0F, 1.0F, Clearance.SIDE),
    /**
     * Above the hotbar and above the channel bar, which is drawn at {@code height - 62} with its
     * label another ten pixels up. The channel bar itself does not use an anchor — it is hardcoded.
     */
    BOTTOM_CENTER(0.5F, 1.0F, Clearance.CENTER),
    BOTTOM_RIGHT(1.0F, 1.0F, Clearance.SIDE);

    /**
     * How far a bottom-anchored element sits above the screen edge. In a holder class because an
     * enum constant's arguments cannot name a static field of the enum itself.
     */
    private static final class Clearance {
        /**
         * Clears the vanilla HUD on the left and right: hotbar 22, xp bar to 29, health row to 39,
         * armor row to 49, plus a 3px margin.
         */
        static final int SIDE = 52;

        /**
         * The same in the centre column, which must additionally clear this mod's own channel bar at
         * {@code height - 62} and its label at {@code height - 72}, plus a 4px margin.
         */
        static final int CENTER = 76;

        private Clearance() {}
    }

    private final float fx;
    private final float fy;
    private final int bottomClearance;

    HudAnchor(float fx, float fy, int bottomClearance) {
        this.fx = fx;
        this.fy = fy;
        this.bottomClearance = bottomClearance;
    }

    /**
     * The element's left edge, clamped so it never leaves the screen.
     *
     * <p>A positive offset always moves the element inward — right from a left anchor, left from a
     * right one — so the same configured value means the same visual gap at either edge.
     *
     * <p>Clamping rather than allowing the offset to push it off is the whole point: a player who set
     * a large offset on a wide monitor and then opened the game on a laptop should find the element
     * moved, not missing.
     */
    public int x(int screenWidth, int elementWidth, int offsetX) {
        int edge = Math.round((screenWidth - elementWidth) * fx);
        int raw = fx == 1.0F ? edge - offsetX : edge + offsetX;
        return clamp(raw, screenWidth, elementWidth);
    }

    /**
     * The element's top edge, clamped the same way, and lifted clear of the hotbar band at a bottom
     * anchor so choosing one does not hide the element behind vanilla's HUD.
     */
    public int y(int screenHeight, int elementHeight, int offsetY) {
        int edge = Math.round((screenHeight - elementHeight) * fy);
        int raw = isBottom() ? edge - bottomClearance - offsetY : edge + offsetY;
        return clamp(raw, screenHeight, elementHeight);
    }

    /**
     * Keeps an element inside its axis. When the element is larger than the screen there is no valid
     * position, so it is pinned to zero rather than given a negative upper bound.
     */
    private static int clamp(int raw, int screenSize, int elementSize) {
        int max = screenSize - elementSize;
        if (max <= 0) return 0;
        return Math.max(0, Math.min(max, raw));
    }

    /**
     * True for the anchors that sit along the bottom, where the hotbar and its overlays already are.
     * These count their offset and their clearance upward from the screen edge.
     */
    public boolean isBottom() {
        return fy == 1.0F;
    }

    /**
     * {@code gui.mcacrime.anchor.<lower>} — the anchor's name in the settings screen. Derived rather
     * than declared, so adding an anchor cannot silently ship without a label.
     */
    public String labelKey() {
        return "gui.mcacrime.anchor." + name().toLowerCase(java.util.Locale.ROOT);
    }
}
