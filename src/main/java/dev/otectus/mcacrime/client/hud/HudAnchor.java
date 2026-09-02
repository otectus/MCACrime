package dev.otectus.mcacrime.client.hud;

/**
 * Where a HUD element sits, and the arithmetic that puts it there.
 *
 * <p>Deliberately free of any Minecraft type so the placement can be unit-tested. Getting an overlay
 * position wrong is not a crash — it is an element drawn half off the screen at one GUI scale and
 * nowhere near the hotbar at another, which is exactly the class of bug that only shows up on
 * somebody else's monitor.
 *
 * <p><b>This class must stay Minecraft-free.</b> It sits in a client package but is named by the
 * client config spec, whose enclosing class is constructed on both sides — so a dedicated server does
 * classload it. Pure Java is fine there; a single client-only import would not be.
 */
public enum HudAnchor {
    TOP_LEFT(0.0F, 0.0F),
    TOP_CENTER(0.5F, 0.0F),
    TOP_RIGHT(1.0F, 0.0F),
    CENTER_LEFT(0.0F, 0.5F),
    CENTER_RIGHT(1.0F, 0.5F),
    BOTTOM_LEFT(0.0F, 1.0F),
    /** Just above the hotbar. The default for the channel bar. */
    BOTTOM_CENTER(0.5F, 1.0F),
    BOTTOM_RIGHT(1.0F, 1.0F);

    private final float fx;
    private final float fy;

    HudAnchor(float fx, float fy) {
        this.fx = fx;
        this.fy = fy;
    }

    /**
     * The element's left edge, clamped so it never leaves the screen.
     *
     * <p>Clamping rather than allowing the offset to push it off is the whole point: a player who set
     * a large offset on a wide monitor and then opened the game on a laptop should find the element
     * moved, not missing.
     */
    public int x(int screenWidth, int elementWidth, int offsetX) {
        int raw = Math.round((screenWidth - elementWidth) * fx) + offsetX;
        return clamp(raw, screenWidth, elementWidth);
    }

    /** The element's top edge, clamped the same way. */
    public int y(int screenHeight, int elementHeight, int offsetY) {
        int raw = Math.round((screenHeight - elementHeight) * fy) + offsetY;
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

    /** True for the anchors that sit along the bottom, where the hotbar and its overlays already are. */
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
