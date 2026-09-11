package dev.otectus.mcacrime;

import dev.otectus.mcacrime.enforcement.AccompliceExposure;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The two ways a helpful relative becomes a wanted one, and the one way they do not.
 *
 * <p>The third case is the one worth a test: a relative who is already wanted is not exposed again. A
 * second warrant is not a second consequence, and re-charging them would re-announce an arrest their
 * family has already been told about.
 */
class AccompliceExposureTest {

    @Test
    void beingSeenIsEnough() {
        assertTrue(AccompliceExposure.exposed(false, true, false, true));
        assertTrue(AccompliceExposure.exposed(false, true, false, false),
                "a witness does not need the implication setting to be on");
    }

    @Test
    void thePrincipalsArrestImplicatesThemOnlyWhenTheSettingIsOn() {
        assertTrue(AccompliceExposure.exposed(false, false, true, true));
        assertFalse(AccompliceExposure.exposed(false, false, true, false));
    }

    @Test
    void helpingUnseenWithTheirPrincipalStillFreeIsNotAnOffenceAnybodyCanCharge() {
        assertFalse(AccompliceExposure.exposed(false, false, false, true));
    }

    @Test
    void somebodyAlreadyWantedIsNotExposedTwice() {
        assertFalse(AccompliceExposure.exposed(true, true, true, true));
    }
}
