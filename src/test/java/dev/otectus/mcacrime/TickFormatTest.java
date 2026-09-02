package dev.otectus.mcacrime;

import dev.otectus.mcacrime.util.TickFormat;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Duration formatting. Two renderings of the same tick count exist on purpose — {@code clock} for
 * things read as a timer running down, {@code compact} for "how much longer" — and the risk with two
 * is that they drift, so both are pinned here.
 */
class TickFormatTest {

    @Test
    void compactSpellsOutTheUnits() {
        assertEquals("0s", TickFormat.compact(0L));
        assertEquals("42s", TickFormat.compact(840L));
        assertEquals("1m 0s", TickFormat.compact(1200L));
        // The literal example from the requirement: a sentence with 1m 42s left must read exactly that.
        assertEquals("1m 42s", TickFormat.compact(2040L));
        assertEquals("59m 59s", TickFormat.compact(71_980L));
        assertEquals("1h 0m 0s", TickFormat.compact(72_000L));
        assertEquals("1h 2m 3s", TickFormat.compact(74_460L));
    }

    @Test
    void compactRoundsUpSoARunningTimerNeverReadsZero() {
        assertEquals("1s", TickFormat.compact(1L));
        assertEquals("1s", TickFormat.compact(19L));
        assertEquals("1s", TickFormat.compact(20L));
        assertEquals("2s", TickFormat.compact(21L));
    }

    @Test
    void compactClampsNegativesRatherThanRenderingThem() {
        assertEquals("0s", TickFormat.compact(-1L));
        assertEquals("0s", TickFormat.compact(Long.MIN_VALUE / 2));
    }

    @Test
    void clockIsUnchanged() {
        assertEquals("0:00", TickFormat.clock(0L));
        assertEquals("1:42", TickFormat.clock(2040L));
        assertEquals("1:00:00", TickFormat.clock(72_000L));
        assertEquals("0:01", TickFormat.clock(1L));
    }

    @Test
    void bothRenderingsAgreeOnTheSecondsTheyShow() {
        for (long ticks : new long[] {0L, 1L, 19L, 20L, 840L, 1200L, 2040L, 71_980L, 72_000L, 74_460L}) {
            long seconds = TickFormat.seconds(ticks);
            long fromCompact = parseCompactSeconds(TickFormat.compact(ticks));
            assertEquals(seconds, fromCompact, "compact(" + ticks + ") disagreed with seconds()");
        }
    }

    private static long parseCompactSeconds(String rendered) {
        long total = 0L;
        for (String part : rendered.split(" ")) {
            long value = Long.parseLong(part.substring(0, part.length() - 1));
            total += switch (part.charAt(part.length() - 1)) {
                case 'h' -> value * 3600L;
                case 'm' -> value * 60L;
                default -> value;
            };
        }
        return total;
    }
}
