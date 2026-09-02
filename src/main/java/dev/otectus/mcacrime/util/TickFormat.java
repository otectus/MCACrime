package dev.otectus.mcacrime.util;

/**
 * Turns a tick count into something a person can read.
 *
 * <p>Every duration in this mod was previously rendered as raw seconds with a hardcoded English
 * {@code "s"} glued on -- on the player card, in {@code /crime status}, and in the jail message. That
 * is both untranslatable and unreadable: a six-minute sentence displayed as "360s" tells a player
 * much less than "6:00".
 *
 * <p>Pure and Minecraft-free, so it unit-tests and so the same formatting is used by the HUD, the
 * screens and the commands rather than three near-identical expressions drifting apart.
 */
public final class TickFormat {

    /** Vanilla ticks per second. */
    public static final long TICKS_PER_SECOND = 20L;

    private TickFormat() {
    }

    /**
     * A clock reading: {@code m:ss} under an hour, {@code h:mm:ss} above it.
     *
     * <p>Rounds up rather than down, so a sentence with a fraction of a second left never reads
     * "0:00" while the player is still serving it.
     */
    public static String clock(long ticks) {
        long totalSeconds = Math.max(0L, (ticks + TICKS_PER_SECOND - 1) / TICKS_PER_SECOND);
        long hours = totalSeconds / 3600L;
        long minutes = (totalSeconds % 3600L) / 60L;
        long seconds = totalSeconds % 60L;
        return hours > 0
                ? String.format("%d:%02d:%02d", hours, minutes, seconds)
                : String.format("%d:%02d", minutes, seconds);
    }

    /**
     * A duration with its units spelled out: {@code 42s}, {@code 1m 42s}, {@code 1h 2m 3s}.
     *
     * <p>The counterpart to {@link #clock}, not a replacement for it. A jail sentence is read as
     * "how much longer do I have to wait", and {@code 1:42} makes a reader work out which half is
     * which; a channel bar or a captivity cap is read as a clock ticking down, where the colon is
     * exactly right. Both round up for the same reason: a timer that still has a fraction of a
     * second on it must never read zero.
     */
    public static String compact(long ticks) {
        long totalSeconds = seconds(ticks);
        long hours = totalSeconds / 3600L;
        long minutes = (totalSeconds % 3600L) / 60L;
        long secs = totalSeconds % 60L;
        if (hours > 0) {
            return hours + "h " + minutes + "m " + secs + "s";
        }
        return minutes > 0 ? minutes + "m " + secs + "s" : secs + "s";
    }

    /** Whole seconds, rounded up. For places that want a bare number to pass as a format argument. */
    public static long seconds(long ticks) {
        return Math.max(0L, (ticks + TICKS_PER_SECOND - 1) / TICKS_PER_SECOND);
    }
}
