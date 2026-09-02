package dev.otectus.mcacrime.client.screen;

import dev.otectus.mcacrime.ledger.Resolution;

/**
 * Text colours for the light panel, and the rule that keeps information legible on it.
 *
 * <p>The screens used to be dark violet, where a saturated colour like {@code 0x55FF55} read
 * perfectly well as text. On vanilla's {@code #C6C6C6} panel the same green is very nearly
 * invisible — light-on-light — and the mod would have quietly stopped communicating the one thing
 * those colours exist to communicate. {@link #onPanel(int)} is the fix: it darkens an information
 * colour until it is genuinely readable against the panel, keeping its hue so that green still
 * means lawful and red still means criminal.
 *
 * <p>Colours are only darkened where they are drawn <em>as text on the panel</em>. A legality chip
 * keeps the bright original, because a saturated square inside a black frame reads fine at any
 * lightness, and so does tooltip text, because the tooltip's own background is dark.
 *
 * <p>This class must stay Minecraft-free, for the same reason {@code HudAnchor} is: not because a
 * server loads it, but because being free of Minecraft is what lets {@code PanelColoursTest} check
 * the contrast of every colour the mod ships without a running client. A rendering bug that only
 * appears on somebody else's monitor is exactly the kind this project tests for.
 */
public final class PanelColours {

    /** The panel body every on-panel colour is measured against. Vanilla's container grey. */
    public static final int FACE = 0xC6C6C6;

    /** Ordinary body text. Vanilla's own container label colour. */
    public static final int TEXT = 0x404040;
    /** Secondary text: descriptions, hints, units. Dimmer, still comfortably readable. */
    public static final int TEXT_MUTED = 0x646464;
    /** Text for something present but unusable. Reads as switched off without vanishing. */
    public static final int TEXT_DISABLED = 0x808080;

    /**
     * The contrast ratio {@link #onPanel(int)} darkens to. 4.5:1 is the WCAG AA threshold for body
     * text; it is a stricter bar than a game usually sets, and the point of setting it is that a
     * colour chosen because it looked right on one monitor cannot silently fail on another.
     */
    public static final double MIN_CONTRAST = 4.5D;

    private PanelColours() {
    }

    /**
     * Darkens an information colour until it reads as text on the panel, preserving its hue.
     *
     * <p>Scaling all three channels by one factor is what keeps the hue: the result is the same
     * colour seen in less light, not a different colour. The factor is found by bisection rather
     * than picked as a constant because no single constant works — {@code 0x55FF55} needs roughly
     * twice the darkening of {@code 0xFF5555} to reach the same contrast, since human luminance is
     * dominated by the green channel. Twenty steps is exact to well under one 8-bit level, and the
     * arithmetic is a few dozen floating-point operations on a handful of rows per frame.
     */
    public static int onPanel(int rgb) {
        double low = 0.0D;
        double high = 1.0D;
        for (int step = 0; step < 20; step++) {
            double mid = (low + high) / 2.0D;
            if (contrast(scale(rgb, mid), FACE) >= MIN_CONTRAST) {
                low = mid;
            } else {
                high = mid;
            }
        }
        return scale(rgb, low);
    }

    /**
     * The colour carrying a case's resolution.
     *
     * <p>Lives here rather than in the dossier screen so the contrast test can see it. It was a
     * private table in one screen, which is precisely how a palette drifts.
     */
    public static int resolution(Resolution resolution) {
        return switch (resolution) {
            case UNRESOLVED -> 0xFF5555;
            case ESCAPED -> 0xFFAA00;
            case SERVED, FINED, PARDONED -> 0x55FF55;
            case EXPIRED -> 0xAAAAAA;
        };
    }

    /** The WCAG contrast ratio between two opaque colours, from 1.0 (identical) to 21.0. */
    public static double contrast(int rgbA, int rgbB) {
        double a = luminance(rgbA);
        double b = luminance(rgbB);
        return (Math.max(a, b) + 0.05D) / (Math.min(a, b) + 0.05D);
    }

    private static int scale(int rgb, double factor) {
        int r = (int) Math.round(((rgb >> 16) & 0xFF) * factor);
        int g = (int) Math.round(((rgb >> 8) & 0xFF) * factor);
        int b = (int) Math.round((rgb & 0xFF) * factor);
        return (r << 16) | (g << 8) | b;
    }

    /** WCAG relative luminance: gamma-expanded channels weighted by how bright the eye finds them. */
    private static double luminance(int rgb) {
        return 0.2126D * expand((rgb >> 16) & 0xFF)
                + 0.7152D * expand((rgb >> 8) & 0xFF)
                + 0.0722D * expand(rgb & 0xFF);
    }

    private static double expand(int channel) {
        double c = channel / 255.0D;
        return c <= 0.03928D ? c / 12.92D : Math.pow((c + 0.055D) / 1.055D, 2.4D);
    }
}
