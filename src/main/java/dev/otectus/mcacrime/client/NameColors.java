package dev.otectus.mcacrime.client;

import dev.otectus.mcacrime.crime.Band;

/** Band → RGB color mapping for client display (nameplates + card). Client-only. */
public final class NameColors {

    public static final int BLUE_RGB = 0x5C9DFF;
    public static final int RED_RGB = 0xFF5555;
    public static final int GREY_RGB = 0xAAAAAA;

    private NameColors() {
    }

    /** The recolor RGB for a band, or {@code null} for GREY (which is left un-colored, non-destructive). */
    public static Integer rgbOrNull(Band band) {
        return switch (band) {
            case BLUE -> BLUE_RGB;
            case RED -> RED_RGB;
            case GREY -> null;
        };
    }

    /** A concrete RGB for every band (GREY gets a readable grey), for the card text. */
    public static int rgb(Band band) {
        return switch (band) {
            case BLUE -> BLUE_RGB;
            case RED -> RED_RGB;
            case GREY -> GREY_RGB;
        };
    }
}
