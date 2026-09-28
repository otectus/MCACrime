package dev.otectus.mcacrime.restraint;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The documented order in which one right-click is interpreted (0.7.5 M2.5).
 *
 * <p>The specification flags the empty hand as genuinely ambiguous — it could mean "free this person"
 * or "take this person into custody" — and the resolution has to be a rule rather than whichever
 * handler happened to subscribe first.
 *
 * <p>The assertion that matters most is stated in its own test below: <b>a release request never
 * starts an escort</b>. Everything else in the order is convenience; that one is the difference
 * between a player freeing a friend and a player accidentally kidnapping them.
 */
class InteractionPriorityTest {

    private static RestraintInteractHandler.Route route(boolean operatorTool,
                                                        boolean restraintItem, boolean matchingKey,
                                                        boolean lockpick, boolean emptyHand,
                                                        boolean crouching, boolean restrained) {
        return RestraintInteractHandler.route(operatorTool, restraintItem, matchingKey,
                lockpick, emptyHand, crouching, restrained);
    }

    @Test
    void aReleaseRequestNeverStartsAnEscort() {
        // Crouching with an empty hand on a restrained subject is a removal, and the escort branch is
        // below it in the order so it cannot be reached from here.
        assertEquals(RestraintInteractHandler.Route.KEYLESS_REMOVAL,
                route(false, false, false, false, true, true, true));
        // A key outranks the escort too, crouching or not.
        assertEquals(RestraintInteractHandler.Route.KEY_REMOVAL,
                route(false, false, true, false, false, false, true));
    }

    @Test
    void anEmptyHandStandingIsAnEscort() {
        assertEquals(RestraintInteractHandler.Route.ESCORT_START,
                route(false, false, false, false, true, false, false));
        // Even on a restrained subject: standing up is the deliberate, different gesture.
        assertEquals(RestraintInteractHandler.Route.ESCORT_START,
                route(false, false, false, false, true, false, true));
    }

    @Test
    void theOperatorToolOutranksEverything() {
        assertEquals(RestraintInteractHandler.Route.OPERATOR_TOOL,
                route(true, true, true, true, false, true, true));
    }

    @Test
    void theOrderIsTheDocumentedOne() {
        assertEquals(RestraintInteractHandler.Route.APPLY_RESTRAINT,
                route(false, true, true, true, false, false, true));
        assertEquals(RestraintInteractHandler.Route.KEY_REMOVAL,
                route(false, false, true, true, false, false, true));
        assertEquals(RestraintInteractHandler.Route.LOCKPICK,
                route(false, false, false, true, false, false, true));
    }

    @Test
    void removalRoutesNeedSomethingToRemove() {
        // A key aimed at somebody wearing nothing falls through to MCA rather than being swallowed.
        assertEquals(RestraintInteractHandler.Route.PASS,
                route(false, false, true, false, false, false, false));
        assertEquals(RestraintInteractHandler.Route.PASS,
                route(false, false, false, true, false, false, false));
        assertEquals(RestraintInteractHandler.Route.PASS,
                route(false, false, false, false, true, true, false));
    }

    @Test
    void anOrdinaryItemIsNotOurs() {
        assertEquals(RestraintInteractHandler.Route.PASS,
                route(false, false, false, false, false, false, true));
    }
}
