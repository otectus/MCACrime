package dev.otectus.mcacrime.client;

import net.minecraft.world.InteractionHand;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The shim's decision table, which is the whole of it that can be tested without a client.
 *
 * <p>Every input is a veto, so the test that matters is the one that flips each of them in turn: a
 * forward that happens when it should not is a duplicate interaction — two crime menus, or a menu on
 * top of a gift — and that is the failure this class exists to make impossible.
 */
class EpicFightInteractShimTest {

    private static boolean forward(long ticksSinceLastForward) {
        return EpicFightInteractShim.shouldForward(true, true, true, InteractionHand.MAIN_HAND,
                true, true, ticksSinceLastForward);
    }

    @Test
    void everyConditionMetForwardsTheInteraction() {
        assertTrue(forward(40L));
    }

    @Test
    void eachSingleMissingConditionVetoesTheForward() {
        assertFalse(EpicFightInteractShim.shouldForward(false, true, true, InteractionHand.MAIN_HAND, true, true, 40L),
                "without Epic Fight nothing cancelled the key for this reason");
        assertFalse(EpicFightInteractShim.shouldForward(true, false, true, InteractionHand.MAIN_HAND, true, true, 40L),
                "an uncancelled event is already being handled by vanilla");
        assertFalse(EpicFightInteractShim.shouldForward(true, true, false, InteractionHand.MAIN_HAND, true, true, 40L),
                "attack and pick-block are not ours to forward");
        assertFalse(EpicFightInteractShim.shouldForward(true, true, true, InteractionHand.MAIN_HAND, false, true, 40L),
                "only MCA villagers");
        assertFalse(EpicFightInteractShim.shouldForward(true, true, true, InteractionHand.MAIN_HAND, true, false, 40L),
                "nothing to forward when this mod's own trigger would not have applied");
    }

    @Test
    void theOffHandIsNotForwarded() {
        assertFalse(EpicFightInteractShim.shouldForward(true, true, true, InteractionHand.OFF_HAND,
                true, true, 40L));
    }

    @Test
    void theThrottleOpensExactlyAtTheCooldown() {
        assertFalse(forward(EpicFightInteractShim.FORWARD_COOLDOWN_TICKS - 1));
        assertTrue(forward(EpicFightInteractShim.FORWARD_COOLDOWN_TICKS));
    }

    @Test
    void aRestraintForwardsRegardlessOfTheWeaponTriggerSwitches() {
        assertTrue(EpicFightInteractShim.crimeTriggerApplies(false, true, false, true, false),
                "capture is a separate mechanic with its own handler and its own rules");
    }

    @Test
    void anArmedHandForwardsOnlyWhileTheTriggerIsOnAndItsSneakRuleIsSatisfied() {
        assertTrue(EpicFightInteractShim.crimeTriggerApplies(true, false, false, false, true));
        assertFalse(EpicFightInteractShim.crimeTriggerApplies(false, false, false, false, true),
                "the server has turned the weapon trigger off");
        assertFalse(EpicFightInteractShim.crimeTriggerApplies(true, true, false, false, true),
                "sneak is required and the player is not sneaking");
        assertTrue(EpicFightInteractShim.crimeTriggerApplies(true, true, true, false, true));
        assertFalse(EpicFightInteractShim.crimeTriggerApplies(true, false, false, false, false),
                "an empty or unqualified hand is left to MCA's own screen");
    }
}
