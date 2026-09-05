package dev.otectus.mcacrime;

import dev.otectus.mcacrime.captivity.RestraintType;
import dev.otectus.mcacrime.enforcement.RestraintVisualType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * The restraint-to-look mapping, asserted to be total.
 *
 * <p>The mapping is a {@code switch} without a {@code default}, so a new {@link RestraintType} is a
 * compile error rather than a silent {@code NONE}. This test guards the other half: that the mapping
 * as written never produces a null and never leaves a real restraint invisible — a captive drawn with
 * no binding at all looks free, which is the one wrong answer that costs the player something.
 */
class RestraintVisualTypeTest {

    @Test
    void everyRestraintMapsToSomething() {
        for (RestraintType type : RestraintType.values()) {
            assertNotNull(RestraintVisualType.of(type), type + " has no visual");
        }
    }

    @Test
    void onlyNoRestraintIsInvisible() {
        for (RestraintType type : RestraintType.values()) {
            RestraintVisualType visual = RestraintVisualType.of(type);
            if (type == RestraintType.NONE) {
                assertEquals(RestraintVisualType.NONE, visual);
            } else {
                assertNotEqualsNone(type, visual);
            }
        }
    }

    /** Rope has to read as rope; both cuff strengths share the metal band on purpose. */
    @Test
    void ropeAndCuffsAreDistinguishable() {
        assertEquals(RestraintVisualType.ROPE, RestraintVisualType.of(RestraintType.ROPE));
        assertEquals(RestraintVisualType.HANDCUFFS, RestraintVisualType.of(RestraintType.CUFFS));
        assertEquals(RestraintVisualType.HANDCUFFS, RestraintVisualType.of(RestraintType.LOCKED_CUFFS));
    }

    /** A null restraint is a missing record, not a freed captive, but drawing nothing is still safe. */
    @Test
    void nullCollapsesToNone() {
        assertEquals(RestraintVisualType.NONE, RestraintVisualType.of(null));
    }

    private static void assertNotEqualsNone(RestraintType type, RestraintVisualType visual) {
        if (visual == RestraintVisualType.NONE) {
            throw new AssertionError(type + " is a real restraint but would be drawn as nothing");
        }
    }
}
