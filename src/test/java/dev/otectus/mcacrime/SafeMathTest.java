package dev.otectus.mcacrime;

import dev.otectus.mcacrime.util.SafeMath;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Saturating arithmetic (0.6.0, T52, audit finding B32).
 *
 * <p>Two claims, and the second is the one that matters. Nothing here changes a result that was
 * already in range — every existing number the mod produces has to come out identical — and nothing
 * here ever wraps, because a wrapped {@code long} is a negative price, and a negative price pays the
 * player to be robbed.
 */
class SafeMathTest {

    @Test
    void inRangeArithmeticIsUnchanged() {
        assertEquals(7L, SafeMath.addSat(3L, 4L));
        assertEquals(-1L, SafeMath.addSat(3L, -4L));
        assertEquals(12L, SafeMath.mulSat(3L, 4L));
        assertEquals(-12L, SafeMath.mulSat(-3L, 4L));
        assertEquals(0L, SafeMath.mulSat(0L, Long.MAX_VALUE));
        assertEquals(5L, SafeMath.mulSat(10L, 0.5D));
        assertEquals(5L, SafeMath.clampLong(5L, 0L, 10L));
    }

    @Test
    void additionSaturatesAtBothEnds() {
        assertEquals(Long.MAX_VALUE, SafeMath.addSat(Long.MAX_VALUE, 1L));
        assertEquals(Long.MAX_VALUE, SafeMath.addSat(Long.MAX_VALUE, Long.MAX_VALUE));
        assertEquals(Long.MIN_VALUE, SafeMath.addSat(Long.MIN_VALUE, -1L));
        assertEquals(Long.MIN_VALUE, SafeMath.addSat(Long.MIN_VALUE, Long.MIN_VALUE));
    }

    @Test
    void multiplicationSaturatesAtTheEndItsSignPointsAt() {
        assertEquals(Long.MAX_VALUE, SafeMath.mulSat(Long.MAX_VALUE, 2L));
        assertEquals(Long.MIN_VALUE, SafeMath.mulSat(Long.MAX_VALUE, -2L));
        assertEquals(Long.MIN_VALUE, SafeMath.mulSat(Long.MIN_VALUE, 2L));
        assertEquals(Long.MAX_VALUE, SafeMath.mulSat(Long.MIN_VALUE, -2L));
    }

    @Test
    void scalingByADoubleSaturatesRatherThanOverflowing() {
        assertEquals(Long.MAX_VALUE, SafeMath.mulSat(Long.MAX_VALUE, 2.0D));
        assertEquals(Long.MIN_VALUE, SafeMath.mulSat(Long.MAX_VALUE, -2.0D));
        assertEquals(Long.MAX_VALUE, SafeMath.mulSat(1_000_000L, 1.0E30D));
    }

    @Test
    void aFactorThatIsNotANumberScalesToNothing() {
        // A hand-edited config or a datapack divide by zero must not turn every price into NaN.
        assertEquals(0L, SafeMath.mulSat(100L, Double.NaN));
        assertEquals(0L, SafeMath.mulSat(100L, Double.POSITIVE_INFINITY));
        assertEquals(0L, SafeMath.mulSat(100L, Double.NEGATIVE_INFINITY));
        assertEquals(2.0D, SafeMath.finiteOr(Double.NaN, 2.0D), 0.0D);
        assertEquals(2.0D, SafeMath.finiteOr(Double.POSITIVE_INFINITY, 2.0D), 0.0D);
        assertEquals(0.5D, SafeMath.finiteOr(0.5D, 2.0D), 0.0D);
    }

    @Test
    void clampingHoldsBothBoundsAndSurvivesAMisorderedRange() {
        assertEquals(10L, SafeMath.clampLong(Long.MAX_VALUE, 0L, 10L));
        assertEquals(0L, SafeMath.clampLong(Long.MIN_VALUE, 0L, 10L));
        assertEquals(10L, SafeMath.clampLong(5L, 10L, 0L), "a mis-ordered range collapses to its lower bound");
    }
}
