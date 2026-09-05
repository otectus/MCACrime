package dev.otectus.mcacrime;

import dev.otectus.mcacrime.compat.mca.client.McaInteractionScreenBridge.ButtonState;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Crime button's gate, which is the whole of spec §"Crime button" that can be tested without a
 * screen: whether the button is clickable, and which sentence explains it when it is not.
 *
 * <p>Worth testing separately from the widget because it is the half that has to agree with the
 * server. A wrong answer here is not a cosmetic bug — it is either a button that does nothing when
 * clicked or a crime the player is told they cannot commit and can.
 */
class CrimeButtonStateTest {

    private static final String REQUIRES_WEAPON = "gui.mcacrime.crime.requires_weapon";
    private static final String MAIN_HAND = "gui.mcacrime.crime.requires_weapon_main_hand";
    private static final String FENCE = "gui.mcacrime.crime.fence_trade";

    @Test
    void anArmedPlayerLookingAtAVillagerMayCommitACrime() {
        ButtonState state = ButtonState.compute(true, true, false, true);
        assertTrue(state.active());
        assertNull(state.tooltipKey(), "an enabled button explains nothing; the label is the explanation");
    }

    @Test
    void anUnarmedPlayerIsToldToDrawAWeapon() {
        ButtonState state = ButtonState.compute(true, false, false, true);
        assertFalse(state.active());
        assertEquals(REQUIRES_WEAPON, state.tooltipKey());
    }

    @Test
    void theTooltipNamesTheHandWhenTheOffHandDoesNotCount() {
        assertEquals(MAIN_HAND, ButtonState.compute(true, false, false, false).tooltipKey());
        assertEquals(REQUIRES_WEAPON, ButtonState.compute(true, false, false, true).tooltipKey());
    }

    @Test
    void aFenceIsReachableUnarmed() {
        ButtonState state = ButtonState.compute(true, false, true, true);
        assertTrue(state.active(), "trading contraband is not coercion, so no weapon is required");
        assertEquals(FENCE, state.tooltipKey());
    }

    @Test
    void aFenceTooltipSurvivesTheOffHandSetting() {
        // The off-hand rule is about weapons, and this row has no weapon in it.
        assertEquals(FENCE, ButtonState.compute(true, false, true, false).tooltipKey());
    }

    /**
     * The server can switch the weapon requirement off entirely. When it has, an empty-handed player
     * is armed as far as this button is concerned -- otherwise the client greys out a menu the server
     * would have opened, which is the exact client/server disagreement the policy packet exists to end.
     */
    @Test
    void aServerWithTheWeaponGateOffEnablesTheButtonForEverybody() {
        ButtonState state = ButtonState.compute(true, false, false, true, false);
        assertTrue(state.active());
        assertNull(state.tooltipKey(), "there is no requirement left to explain");
    }

    @Test
    void theGateOffStillNeedsATarget() {
        assertFalse(ButtonState.compute(false, false, false, true, false).active(),
                "no villager under the cursor is not a weapon problem, and no weapon rule fixes it");
    }

    @Test
    void theOldFourArgumentGateStillRequiresAWeapon() {
        // The overload the rest of the suite uses: it must keep meaning "a weapon is required",
        // or every row above would start passing for the wrong reason.
        assertFalse(ButtonState.compute(true, false, false, true).active());
    }

    @Test
    void anArmedPlayerAtAFenceGetsThePlainEnabledButton() {
        ButtonState state = ButtonState.compute(true, true, true, true);
        assertTrue(state.active());
        assertNull(state.tooltipKey());
    }

    @Test
    void noTargetIsNeverActive() {
        for (boolean armed : new boolean[]{false, true}) {
            for (boolean fence : new boolean[]{false, true}) {
                assertFalse(ButtonState.compute(false, armed, fence, true).active(),
                        "armed=" + armed + " fence=" + fence + " without a resolved villager");
            }
        }
    }

    @Test
    void everyRowOfTheMatrixIsDecided() {
        // Totality: no combination may throw, and no disabled button may be left without a reason.
        for (boolean found : new boolean[]{false, true}) {
            for (boolean armed : new boolean[]{false, true}) {
                for (boolean fence : new boolean[]{false, true}) {
                    for (boolean offHand : new boolean[]{false, true}) {
                        ButtonState state = ButtonState.compute(found, armed, fence, offHand);
                        assertEquals(found && (armed || fence), state.active());
                        if (!armed) {
                            assertTrue(state.tooltipKey() != null && state.tooltipKey().startsWith("gui.mcacrime.crime."),
                                    "an unarmed row must explain itself");
                        }
                    }
                }
            }
        }
    }
}
