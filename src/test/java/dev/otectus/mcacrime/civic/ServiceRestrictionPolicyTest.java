package dev.otectus.mcacrime.civic;

import dev.otectus.mcacrime.crime.Band;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The refusal rule, and above all the thing it must never do.
 *
 * <p>Reference §11.5's line about food, water and care staying reachable is the only assertion here
 * that is really about safety rather than flavour: a mod that can lock a player out of essentials has
 * built a soft ban with no appeal, and the rule is checked first in the policy precisely so that no
 * condition added later can reach past it. The matrix below exists to keep it that way.
 */
class ServiceRestrictionPolicyTest {

    private static boolean refused(ServiceKind kind, Band band, boolean wanted, int open,
                                   double grievance) {
        return ServiceRestrictionPolicy.decide(kind, band, wanted, open, grievance).refused();
    }

    @Test
    void anEssentialServiceIsNeverRefused() {
        for (ServiceKind kind : new ServiceKind[] {
                ServiceKind.ESSENTIAL_FOOD, ServiceKind.ESSENTIAL_SHELTER }) {
            assertFalse(refused(kind, Band.RED, true, 9, 1.0D),
                    kind.id() + " must stay reachable for the worst offender this mod can describe, or "
                            + "there is no route back to lawful standing");
        }
    }

    @Test
    void standingAloneRefusesNothing() {
        assertFalse(refused(ServiceKind.TRADE, Band.RED, false, 0, 0.0D),
                "reference §11.5: a low band must not automatically disable every service");
        assertFalse(refused(ServiceKind.LUXURY, Band.RED, false, 0, 0.0D),
                "an outlaw with nothing open here is still a customer here");
    }

    @Test
    void aPublicCaseInThisSettlementRefusesOrdinaryTrade() {
        assertTrue(refused(ServiceKind.TRADE, Band.RED, true, 2, 0.0D));
        assertEquals(ServiceRestrictionPolicy.Verdict.REFUSED_PUBLIC,
                ServiceRestrictionPolicy.decide(ServiceKind.TRADE, Band.RED, true, 2, 0.0D).verdict());
    }

    @Test
    void beingWantedSomewhereElseCloseNoShopHere() {
        assertFalse(refused(ServiceKind.TRADE, Band.RED, true, 0, 0.0D),
                "wanted with nothing this settlement knows about is a warrant from elsewhere, and a "
                        + "warrant from elsewhere must not close this village's shops");
    }

    @Test
    void aLuxuryIsWithheldFromAnOutlawWithAnOpenCaseEvenWithoutAWarrant() {
        assertTrue(refused(ServiceKind.LUXURY, Band.RED, false, 1, 0.0D));
        assertFalse(refused(ServiceKind.TRADE, Band.RED, false, 1, 0.0D),
                "the same facts must not close ordinary trade: that is the difference between a "
                        + "settlement being wary and a settlement being a wall");
    }

    @Test
    void aFenceDoesNotCareThatYouAreWanted() {
        assertFalse(refused(ServiceKind.FENCE, Band.RED, true, 5, 0.0D),
                "the back room's whole trade is with people the law is after");
    }

    @Test
    void aFenceStillRefusesSomebodyItPersonallyFears() {
        assertTrue(refused(ServiceKind.FENCE, Band.BLUE, false, 0, 0.9D));
        assertEquals(ServiceRestrictionPolicy.Verdict.REFUSED_PERSONAL,
                ServiceRestrictionPolicy.decide(ServiceKind.FENCE, Band.BLUE, false, 0, 0.9D).verdict());
    }

    @Test
    void aPersonalRefusalFadesWithTheMemoryThatCausedIt() {
        double threshold = ServiceRestrictionPolicy.DEFAULT_GRIEVANCE_THRESHOLD;

        assertTrue(refused(ServiceKind.TRADE, Band.BLUE, false, 0, threshold),
                "at the threshold the villager still remembers it");
        assertFalse(refused(ServiceKind.TRADE, Band.BLUE, false, 0, threshold - 0.01D),
                "one notch of decay below it and they serve again -- nothing is cached, so the ban "
                        + "cannot outlive the memory");
        assertFalse(refused(ServiceKind.TRADE, Band.BLUE, false, 0, 0.0D));
    }

    @Test
    void everyRefusalExplainsItselfAndNamesTheRouteBack() {
        ServiceRestrictionPolicy.Decision personal =
                ServiceRestrictionPolicy.decide(ServiceKind.TRADE, Band.GREY, false, 0, 1.0D);
        ServiceRestrictionPolicy.Decision publicCase =
                ServiceRestrictionPolicy.decide(ServiceKind.TRADE, Band.RED, true, 1, 0.0D);

        for (ServiceRestrictionPolicy.Decision decision : new ServiceRestrictionPolicy.Decision[] {
                personal, publicCase }) {
            assertTrue(decision.refused());
            assertFalse(decision.reasonKey().isBlank(), "a bare 'no' is the failure §11.5 is about");
            assertFalse(decision.repairKey().isBlank(), "and so is a 'no' with no way back");
        }
    }

    @Test
    void anUnknownServiceIsServed() {
        assertFalse(ServiceRestrictionPolicy.decide(null, Band.RED, true, 9, 1.0D).refused(),
                "the safe direction for anything this rule does not understand is to serve");
        assertFalse(ServiceRestrictionPolicy.Decision.allowed().refused());
    }

    @Test
    void aNonsenseGrievanceCannotRefuseByAccident() {
        // Not finite reads as no grievance rather than as an enormous one: the direction that serves
        // the customer is the only safe one for a number that arrived broken.
        assertFalse(refused(ServiceKind.TRADE, Band.GREY, false, 0, Double.NaN));
        assertFalse(refused(ServiceKind.TRADE, Band.GREY, false, 0, Double.POSITIVE_INFINITY));
        assertFalse(refused(ServiceKind.TRADE, Band.GREY, false, 0, -5.0D));
    }
}
