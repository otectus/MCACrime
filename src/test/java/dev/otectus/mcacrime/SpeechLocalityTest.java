package dev.otectus.mcacrime;

import dev.otectus.mcacrime.dialogue.SpeechLocality;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A villager's line reaches the same level within the speech radius, and nowhere else (0.7.7).
 *
 * <p>The multiplayer report behind it: a reaction ending in a chunk another player kept loaded told the
 * offender "I would rather not speak to you" from across the map, or from another dimension.
 */
class SpeechLocalityTest {

    private static final double RADIUS = 16.0D;

    @Test
    void aListenerBesideTheSpeakerHearsTheLine() {
        assertTrue(SpeechLocality.reaches(true, 3.0D * 3.0D, RADIUS));
        assertTrue(SpeechLocality.reaches(true, 0.0D, RADIUS));
    }

    @Test
    void theRadiusItselfStillHears() {
        assertTrue(SpeechLocality.reaches(true, RADIUS * RADIUS, RADIUS));
        assertFalse(SpeechLocality.reaches(true, Math.nextUp(RADIUS * RADIUS), RADIUS));
    }

    @Test
    void aListenerFortyBlocksAwayHearsNothing() {
        assertFalse(SpeechLocality.reaches(true, 40.0D * 40.0D, RADIUS));
    }

    @Test
    void anotherDimensionHearsNothingAtAnyDistance() {
        assertFalse(SpeechLocality.reaches(false, 0.0D, RADIUS),
                "the same coordinates in another level are not earshot");
        assertFalse(SpeechLocality.reaches(false, 1.0D, 64.0D));
    }

    @Test
    void nonsenseInputsNeverDeliver() {
        assertFalse(SpeechLocality.reaches(true, Double.NaN, RADIUS));
        assertFalse(SpeechLocality.reaches(true, -1.0D, RADIUS));
        assertFalse(SpeechLocality.reaches(true, 1.0D, 0.0D));
        assertFalse(SpeechLocality.reaches(true, 1.0D, Double.NaN));
    }
}
