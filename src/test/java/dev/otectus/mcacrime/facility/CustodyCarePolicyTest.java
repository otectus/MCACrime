package dev.otectus.mcacrime.facility;

import dev.otectus.mcacrime.compat.TownsteadNeedsView;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The rule a lawful sentence must never break: it is not allowed to be the thing that kills somebody.
 *
 * <p>Custody takes away the travel a villager feeds themselves with, so a settlement that tracks
 * hunger turns a long sentence into a slow execution unless custody carries its own care. The outcome
 * of last resort is {@code CUSTODY_RECOVERY}, and the assertion that matters most here is a negative
 * one: it is never "served" and never a pardon, because both of those clear a liability nobody
 * discharged.
 */
class CustodyCarePolicyTest {

    private static TownsteadNeedsView needs(int hunger, int thirst, boolean collapsed) {
        return new TownsteadNeedsView(true, hunger, 0f, 0f, thirst, 0, 0f, 0, collapsed, false);
    }

    private static final CustodyCarePolicy.Supply BREAD = new CustodyCarePolicy.Supply(0, true, false);
    private static final CustodyCarePolicy.Supply WATER = new CustodyCarePolicy.Supply(1, false, true);

    @Test
    void aCriticalPrisonerWithNoSuppliesEntersRecovery() {
        CustodyCarePolicy.Decision decision = CustodyCarePolicy.decide(needs(2, 18, false), List.of(),
                true, null);

        assertEquals(CustodyCarePolicy.Action.CUSTODY_RECOVERY, decision.action());
        assertFalse(decision.reason().isBlank(), "an operator has to be able to see why a sentence stopped");
    }

    @Test
    void recoveryIsNeverAReleaseOutcome() {
        for (CustodyCarePolicy.Action action : CustodyCarePolicy.Action.values()) {
            String name = action.name().toLowerCase(java.util.Locale.ROOT);
            assertFalse(name.contains("served") || name.contains("pardon") || name.contains("release"),
                    "custody care must not be able to express an outcome that clears liability; "
                            + action + " looks like one");
        }
    }

    @Test
    void aCollapsedPrisonerWithNothingUsableEntersRecoveryEvenWhenFedAndWatered() {
        CustodyCarePolicy.Decision decision = CustodyCarePolicy.decide(needs(90, 18, true), List.of(),
                true, null);

        assertEquals(CustodyCarePolicy.Action.CUSTODY_RECOVERY, decision.action(),
                "collapse is the settlement's own judgement that this villager cannot go on");
    }

    @Test
    void aStarvingPrisonerWithOnlyWaterStillEntersRecovery() {
        CustodyCarePolicy.Decision decision = CustodyCarePolicy.decide(needs(2, 18, false),
                List.of(WATER), true, null);

        assertEquals(CustodyCarePolicy.Action.CUSTODY_RECOVERY, decision.action(),
                "a barrel of water does not feed a starving villager, and calling that 'supplies present' "
                        + "is how a prisoner is left in a cell with nothing they can use");
    }

    @Test
    void aStarvingPrisonerWithFoodIsFed() {
        CustodyCarePolicy.Decision decision = CustodyCarePolicy.decide(needs(2, 18, false),
                List.of(WATER, BREAD), true, null);

        assertEquals(CustodyCarePolicy.Action.FEED, decision.action());
        assertEquals(BREAD.slot(), decision.slot());
    }

    @Test
    void aCriticalPrisonerWhoseSuppliesCannotBeDeliveredEntersRecovery() {
        CustodyCarePolicy.Decision decision = CustodyCarePolicy.decide(needs(2, 18, false),
                List.of(BREAD), false, null);

        assertEquals(CustodyCarePolicy.Action.CUSTODY_RECOVERY, decision.action(),
                "food in the room that MCA: Crime cannot hand over is food the prisoner is not eating");
        assertTrue(decision.reason().contains("consumption_in_custody"),
                "the reason must name the missing capability, or nobody can act on it");
    }

    @Test
    void aMerelyHungryPrisonerWithNoWayToBeFedIsReportedRatherThanSilentlySkipped() {
        CustodyCarePolicy.Decision decision = CustodyCarePolicy.decide(needs(40, 18, false),
                List.of(BREAD), false, null);

        assertEquals(CustodyCarePolicy.Action.FEED_UNAVAILABLE, decision.action());
        assertEquals(BREAD.slot(), decision.slot());
    }

    @Test
    void aWellFedPrisonerIsLeftAlone() {
        assertEquals(CustodyCarePolicy.Action.OK,
                CustodyCarePolicy.decide(needs(90, 18, false), List.of(BREAD), true, null).action());
    }

    @Test
    void untrackedNeedsDecideNothing() {
        CustodyCarePolicy.Decision decision =
                CustodyCarePolicy.decide(TownsteadNeedsView.untracked(), List.of(), true, null);

        assertEquals(CustodyCarePolicy.Action.OK, decision.action(),
                "an untracked reading is all zeroes; treating it as starvation would release every "
                        + "prisoner on a server with no settlement mod");

        assertEquals(CustodyCarePolicy.Action.OK,
                CustodyCarePolicy.decide(null, List.of(), true, null).action());
    }

    @Test
    void aThirstyPrisonerWithOnlyDrinkIsGivenIt() {
        CustodyCarePolicy.Decision decision = CustodyCarePolicy.decide(needs(90, 6, false),
                List.of(WATER), true, null);

        assertEquals(CustodyCarePolicy.Action.FEED, decision.action());
        assertEquals(WATER.slot(), decision.slot());
    }

    @Test
    void aHungryPrisonerWithNothingSuitableIsNotYetInRecovery() {
        CustodyCarePolicy.Decision decision = CustodyCarePolicy.decide(needs(40, 18, false), List.of(),
                true, null);

        assertEquals(CustodyCarePolicy.Action.OK, decision.action(),
                "short of a meal is not critical; suspending a sentence there would make every cell a "
                        + "revolving door");
    }
}
