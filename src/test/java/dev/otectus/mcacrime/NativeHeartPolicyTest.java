package dev.otectus.mcacrime;

import dev.otectus.mcacrime.relationship.NativeHeartPolicy;
import org.junit.jupiter.api.Test;

import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * What MCA's own killing and hurt penalties may cost a player (0.7.7), one row of the order per test.
 *
 * <p>MCA charges 20 hearts to every villager within 32 blocks and to every loaded relative, with no
 * sight check. The rows: an exempt thief fight costs nothing, the penalty switch off costs nothing, the
 * witness switch off restores MCA, a masked hidden killer costs nothing, a mourner who did not identify
 * the killer loses nothing, and only then does MCA's value stand.
 */
class NativeHeartPolicyTest {

    private static final int MCA = -20;
    private static final BooleanSupplier YES = () -> true;
    private static final BooleanSupplier NO = () -> false;
    /** Fails if asked: proves a row decided before the identity question was needed. */
    private static final BooleanSupplier UNASKED = () -> fail("identity was asked after the order had decided");

    private static int tragedy(boolean thief, boolean penalty, boolean witness, BooleanSupplier masked,
                               BooleanSupplier identified) {
        return NativeHeartPolicy.tragedyDelta(MCA, true, thief, penalty, witness, masked, identified);
    }

    @Test
    void row1AnExemptThiefFightCostsNothing() {
        assertEquals(0, tragedy(true, true, true, UNASKED, UNASKED));
        assertEquals(0, tragedy(true, true, false, UNASKED, UNASKED), "even with the witness rule off");
    }

    @Test
    void row2ThePenaltySwitchOffCostsNothingForAnybody() {
        assertEquals(0, tragedy(false, false, true, UNASKED, UNASKED));
        assertEquals(0, tragedy(false, false, false, UNASKED, UNASKED), "the switch outranks the witness rule");
    }

    @Test
    void row3TheWitnessSwitchOffRestoresMcaNativeValue() {
        assertEquals(MCA, tragedy(false, true, false, UNASKED, UNASKED),
                "a neighbour behind a wall loses MCA's twenty hearts with the witness rule off");
    }

    @Test
    void row4AMaskedHiddenKillerCostsNothing() {
        assertEquals(0, tragedy(false, true, true, YES, UNASKED));
    }

    @Test
    void row5AMournerWhoDidNotIdentifyTheKillerLosesNothing() {
        assertEquals(0, tragedy(false, true, true, NO, NO), "the walled-off neighbour keeps its hearts");
    }

    @Test
    void row6ASightedMournerLosesMcaValue() {
        assertEquals(MCA, tragedy(false, true, true, NO, YES));
    }

    @Test
    void onlyANegativePlayerDeltaIsEverChanged() {
        assertEquals(MCA, NativeHeartPolicy.tragedyDelta(MCA, false, true, false, true, YES, NO),
                "a mob's kill is MCA's business");
        assertEquals(5, NativeHeartPolicy.tragedyDelta(5, true, true, false, true, YES, NO));
        assertEquals(0, NativeHeartPolicy.tragedyDelta(0, true, false, true, true, NO, NO));
    }

    @Test
    void hurtHeartsFollowThiefMaskAndHoldBack() {
        assertEquals(-7, NativeHeartPolicy.hurtDelta(-7, true, false, false, false));
        assertEquals(0, NativeHeartPolicy.hurtDelta(-7, true, true, false, false), "thief fight");
        assertEquals(0, NativeHeartPolicy.hurtDelta(-7, false, true, false, false), "thief fight, any attacker");
        assertEquals(0, NativeHeartPolicy.hurtDelta(-7, true, false, true, false), "masked and hidden");
        assertEquals(0, NativeHeartPolicy.hurtDelta(-7, true, false, false, true), "held back as minor harm");
        assertEquals(-7, NativeHeartPolicy.hurtDelta(-7, false, false, true, true), "only players are held back");
        assertEquals(3, NativeHeartPolicy.hurtDelta(3, true, true, true, true));
    }

    @Test
    void crimeOwnHeartsSkipAHiddenMaskedOffender() {
        assertFalse(NativeHeartPolicy.crimeHeartsApply(true, true));
        assertTrue(NativeHeartPolicy.crimeHeartsApply(true, false), "a mask that hides nothing hides nothing");
        assertTrue(NativeHeartPolicy.crimeHeartsApply(false, true));
    }

    @Test
    void crimeKillingLossNeverStacksOnMcaAndHonoursTheSwitch() {
        assertTrue(NativeHeartPolicy.crimeKillingLossApplies(true, true, true, false));
        assertFalse(NativeHeartPolicy.crimeKillingLossApplies(true, true, true, true), "MCA already charged them");
        assertFalse(NativeHeartPolicy.crimeKillingLossApplies(true, false, true, false), "penalty switch off");
        assertFalse(NativeHeartPolicy.crimeKillingLossApplies(true, true, false, false),
                "with the hooks degraded MCA charges everybody, so Crime adds nothing");
        assertTrue(NativeHeartPolicy.crimeKillingLossApplies(false, false, false, true),
                "a non-lethal crime is not a killing and none of the killing rules apply");
    }
}
