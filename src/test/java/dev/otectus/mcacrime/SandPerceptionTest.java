package dev.otectus.mcacrime;

import dev.otectus.mcacrime.detect.PerceptionRules;
import dev.otectus.mcacrime.effect.SandExposurePolicy;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a sanded witness can and cannot do (0.7.2 §13.5, invariant 12).
 *
 * <p>The interesting assertions here are the negative ones. Sand is easy to implement as "this NPC
 * perceives nothing", and that would quietly break hearing, remembered identity and the close-range
 * reaction that keeps a blinded guard from being harmless.
 */
class SandPerceptionTest {

    private static final double CLOSE = SandExposurePolicy.CLOSE_CONTACT_RANGE;

    private static PerceptionRules.Result sanded(double distance, double soundRadius) {
        return PerceptionRules.evaluate(new PerceptionRules.Input(distance, 20, soundRadius, true, true,
                false, false, false, false, false, 1, 1, false, true, CLOSE));
    }

    private static PerceptionRules.Result clear(double distance, double soundRadius) {
        return PerceptionRules.evaluate(new PerceptionRules.Input(distance, 20, soundRadius, true, true,
                false, false, false, false, false, 1, 1, false, false, CLOSE));
    }

    @Test
    void sandBlocksFreshSightBeyondCloseContact() {
        assertFalse(sanded(6, 0).sawAct());
        assertFalse(sanded(6, 0).identifiesActor());
        assertTrue(clear(6, 0).sawAct(), "the same observer without sand sees it perfectly well");
    }

    @Test
    void closeContactStillSeesAndStillIdentifies() {
        assertTrue(sanded(CLOSE, 0).sawAct(), "somebody at arm's reach is still noticed");
        assertTrue(sanded(CLOSE, 0).identifiesActor());
        assertFalse(sanded(CLOSE + 0.01D, 0).sawAct(), "and one step further away is not");
    }

    @Test
    void hearingIsCompletelyUntouched() {
        PerceptionRules.Result result = sanded(8, 24);
        assertTrue(result.heardAct(), "sand is in the eyes, not the ears");
        assertTrue(result.aware(), "a sanded villager still knows something happened");
        assertFalse(result.sawAct());
        assertEquals(clear(8, 24).heardAct(), result.heardAct());
    }

    @Test
    void sandDoesNotMasqueradeAsSleep() {
        // Sleep zeroes hearing as well; sand must not, or "blinded" would silently mean "unconscious"
        // and a sanded NPC would stop reacting to anything at all.
        PerceptionRules.Result asleep = PerceptionRules.evaluate(new PerceptionRules.Input(2, 20, 24, true,
                true, false, true, false, false, false, 1, 1, false, false, CLOSE));
        assertFalse(asleep.aware());
        assertTrue(sanded(2, 24).aware());
    }

    @Test
    void anObserverWhoIsNotSandedIsUnaffectedByTheNewFields() {
        // The thirteen-argument constructor is what every pre-0.7.2 caller uses; it must mean "not
        // sanded" and produce exactly the answer it always did.
        PerceptionRules.Result legacy = PerceptionRules.evaluate(new PerceptionRules.Input(6, 20, 0, true,
                true, false, false, false, false, false, 1, 1, false));
        assertEquals(clear(6, 0), legacy);
    }
}
