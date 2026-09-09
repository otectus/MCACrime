package dev.otectus.mcacrime.client.hud;

/** Shared bounds for Heat and Sentence. Coordinates are GUI pixels, independent of GUI scale. */
public final class CrimeHudLayout {
    private CrimeHudLayout() { }

    public record Placement(int x, int y, float scale) { }

    public static Placement place(HudAnchor anchor, int width, int height, int boxWidth, int boxHeight,
                                  int offsetX, int offsetY) {
        if (anchor == HudAnchor.BOTTOM_LEFT) {
            // Chat ends 40px above the bottom; its delayed-message strip uses another 9px.
            // The hotbar starts 91px left of centre. Fit the whole panel into the remaining pocket.
            int x = Math.max(2, Math.min(offsetX, Math.max(2, width / 2 - 101)));
            int bottom = Math.max(2, Math.min(offsetY, 8));
            float scale = Math.min(1F, Math.min(Math.max(1, width / 2 - 95 - x) / (float) boxWidth,
                    (30 - bottom) / (float) boxHeight));
            return new Placement(x, height - bottom - (int) Math.ceil(boxHeight * scale), scale);
        }
        return new Placement(anchor.x(width, boxWidth, offsetX), anchor.y(height, boxHeight, offsetY), 1F);
    }

    /** Move only the former default; deliberate custom placements survive the upgrade. */
    public static HudAnchor migratedAnchor(HudAnchor anchor, int x, int y) {
        return anchor == HudAnchor.TOP_LEFT && x == 4 && y == 4 ? HudAnchor.BOTTOM_LEFT : anchor;
    }
}
