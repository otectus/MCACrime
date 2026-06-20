package dev.otectus.mcacrime.crime;

/**
 * A player's long-term legal/moral standing, derived from <b>Karma only</b> (spec §1.1). Bands never
 * read Heat — a Red outlaw who has lain low is still Red; a Grey player who just committed a witnessed
 * murder is still Grey (but Wanted, which is a separate Heat-only axis).
 *
 * <p>This class is pure and dependency-free so the band math is unit-testable without a running game.
 * The defensive fallback in {@link #fromKarma} guarantees a sane band even if a server owner mis-orders
 * the thresholds (the {@code /crime validate} command and load-time validation report the bad config;
 * this class simply refuses to produce nonsense).
 */
public enum Band {
    BLUE,
    GREY,
    RED;

    /** Spec §1.1 default: Blue at Karma ≥ +100. */
    public static final long DEFAULT_BLUE_THRESHOLD = 100L;
    /** Spec §1.1 default: Red at Karma ≤ −100. */
    public static final long DEFAULT_RED_THRESHOLD = -100L;

    /**
     * Derives the band from karma. {@code blueThreshold} is the inclusive lower bound for Blue and
     * {@code redThreshold} the inclusive upper bound for Red; anything between is Grey. If the
     * thresholds are mis-ordered ({@code blueThreshold <= redThreshold}) the defaults (+100 / −100)
     * are used instead, so a broken config can never invert the band logic.
     */
    public static Band fromKarma(long karma, long blueThreshold, long redThreshold) {
        long blue = blueThreshold;
        long red = redThreshold;
        if (blue <= red) {
            blue = DEFAULT_BLUE_THRESHOLD;
            red = DEFAULT_RED_THRESHOLD;
        }
        if (karma >= blue) {
            return BLUE;
        }
        if (karma <= red) {
            return RED;
        }
        return GREY;
    }

    /** Convenience overload using the spec defaults; handy for tests and fallbacks. */
    public static Band fromKarma(long karma) {
        return fromKarma(karma, DEFAULT_BLUE_THRESHOLD, DEFAULT_RED_THRESHOLD);
    }

    /** The lowercase enum name, used as a translation-key suffix ({@code mcacrime.band.<lower>}). */
    public String lower() {
        return name().toLowerCase(java.util.Locale.ROOT);
    }
}
