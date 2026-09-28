package dev.otectus.mcacrime.restraint;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * One test per line of the specification's §14.1 table (0.7.5 M4.8), plus §3.19's three capital rows.
 *
 * <p>The bridge is the only class allowed to turn a physical event into a legal one, and its decisions
 * are pure enum-returning functions precisely so this file can exist: every row of the table can be
 * asserted with no world, no entity and no server, and a future change to one row cannot quietly
 * change another.
 */
class CustodyTransitionTest {

    // ---------------------------------------------------------------- applying

    /** Row: player applies first involuntary restraint to an eligible civilian or player. */
    @Test
    void firstInvoluntaryRestraintBeginsUnlawfulCustody() {
        assertEquals(CustodyTransitionService.Application.BEGIN_UNLAWFUL_CUSTODY,
                CustodyTransitionService.classifyApplication(false, false, false, true));
    }

    /** Row: adds another restraint, hood or anchor during that custody. */
    @Test
    void anotherSlotDuringACustodyIsPhysicalOnly() {
        assertEquals(CustodyTransitionService.Application.PHYSICAL_ONLY,
                CustodyTransitionService.classifyApplication(false, false, true, true),
                "no duplicate kidnapping, and the custody age is not reset");
    }

    /** Row: guard restrains a valid suspect. */
    @Test
    void aLawfulAuthorityHasAlreadyDecidedEverything() {
        assertEquals(CustodyTransitionService.Application.LAWFUL_AUTHORITY,
                CustodyTransitionService.classifyApplication(false, true, false, true));
        assertEquals(CustodyTransitionService.Application.LAWFUL_AUTHORITY,
                CustodyTransitionService.classifyApplication(false, true, true, true),
                "an arrest of somebody already held is still the arrest path's business");
    }

    /** Row: player restrains a legitimate bounty target. */
    @Test
    void aBountyClaimIsTheSameLawfulRow() {
        // A citizen's arrest reaches the bridge as a lawful authority, because CustodyService has
        // already routed it to the lawful path -- physical possession alone creates no claim.
        assertEquals(CustodyTransitionService.Application.LAWFUL_AUTHORITY,
                CustodyTransitionService.classifyApplication(false, true, false, true));
    }

    /** Rule: a self-applied hood is not a kidnapping. */
    @Test
    void selfApplicationIsNeverAKidnapping() {
        assertEquals(CustodyTransitionService.Application.NONE,
                CustodyTransitionService.classifyApplication(true, false, false, true));
        assertEquals(CustodyTransitionService.Application.NONE,
                CustodyTransitionService.classifyApplication(true, true, true, true),
                "voluntary demonstration and administrative testing produce nothing either");
    }

    @Test
    void anIneligibleSubjectProducesNoCustody() {
        assertEquals(CustodyTransitionService.Application.NONE,
                CustodyTransitionService.classifyApplication(false, false, false, false));
    }

    // ---------------------------------------------------------------- removing

    /** Row: subject removes a single restraint. */
    @Test
    void removingOneRestraintIsNotARelease() {
        assertEquals(CustodyTransitionService.Removal.PHYSICAL_ONLY,
                CustodyTransitionService.classifyRemoval(false, true, true, true),
                "something is still holding them; the escape is assessed against what is left");
    }

    /** Row: subject escapes unlawful custody. */
    @Test
    void escapeFromUnlawfulCustodyCostsTheVictimNothing() {
        assertEquals(CustodyTransitionService.Removal.ESCAPE_FROM_UNLAWFUL_CUSTODY,
                CustodyTransitionService.classifyRemoval(false, false, true, false));
    }

    /** Row: subject defeats lawful confinement. */
    @Test
    void defeatingLawfulConfinementIsExactlyOneJailbreak() {
        assertEquals(CustodyTransitionService.Removal.DEFEATED_LAWFUL_CONFINEMENT,
                CustodyTransitionService.classifyRemoval(false, false, true, true));
    }

    /** Row: caregiver temporarily removes gear. */
    @Test
    void anAuthorisedRemovalIsAnEquipmentChangeAndNotAnEscape() {
        assertEquals(CustodyTransitionService.Removal.AUTHORISED_EQUIPMENT_CHANGE,
                CustodyTransitionService.classifyRemoval(true, false, true, true),
                "a caregiver taking cuffs off to treat somebody has not helped them escape");
        assertEquals(CustodyTransitionService.Removal.AUTHORISED_EQUIPMENT_CHANGE,
                CustodyTransitionService.classifyRemoval(true, false, true, false),
                "and it is not a pardon either");
    }

    @Test
    void authorityIsCheckedBeforeWhatIsLeft() {
        // The ordering is the design: an authorised removal is an equipment change even at the instant
        // when nothing at all is holding the subject.
        assertEquals(CustodyTransitionService.Removal.AUTHORISED_EQUIPMENT_CHANGE,
                CustodyTransitionService.classifyRemoval(true, false, true, true));
    }

    @Test
    void takingGearOffSomebodyNobodyIsHoldingChangesNothingLegal() {
        assertEquals(CustodyTransitionService.Removal.PHYSICAL_ONLY,
                CustodyTransitionService.classifyRemoval(false, false, false, false),
                "a player untaping a friend is not a release, because there was no custody");
    }

    // ---------------------------------------------------------------- what counts as still held

    @Test
    void anyOneRemainingClaimIsEnoughToNotBeAnEscape() {
        assertTrue(CustodyTransitionService.anyClaimRemains(true, false, false, false), "gear");
        assertTrue(CustodyTransitionService.anyClaimRemains(false, true, false, false), "a chain");
        assertTrue(CustodyTransitionService.anyClaimRemains(false, false, true, false), "an escort");
        assertTrue(CustodyTransitionService.anyClaimRemains(false, false, false, true), "a device");
        assertFalse(CustodyTransitionService.anyClaimRemains(false, false, false, false));
    }

    @Test
    void takingTheCuffsOffSomebodyStillChainedToAFenceIsNotAnEscape() {
        boolean remains = CustodyTransitionService.anyClaimRemains(false, true, false, false);
        assertEquals(CustodyTransitionService.Removal.PHYSICAL_ONLY,
                CustodyTransitionService.classifyRemoval(false, remains, true, true),
                "otherwise one prisoner files four jailbreaks on the way out");
    }

    // ---------------------------------------------------------------- the three capital rows (§3.19)

    @Test
    void condemnedInCustodyIsTheRestingState() {
        assertEquals(CustodyTransitionService.Capital.CONDEMNED_IN_CUSTODY,
                CustodyTransitionService.capitalState(true, false, false),
                "a capital binding exists; confinement rules are otherwise unchanged");
    }

    @Test
    void onlyPendingExecutionAuthorisesTheDevice() {
        assertEquals(CustodyTransitionService.Capital.PENDING_EXECUTION,
                CustodyTransitionService.capitalState(true, true, false));
    }

    @Test
    void executedIsConfirmedDeathAndNothingLess() {
        assertEquals(CustodyTransitionService.Capital.EXECUTED,
                CustodyTransitionService.capitalState(true, true, true));
        assertEquals(CustodyTransitionService.Capital.EXECUTED,
                CustodyTransitionService.capitalState(true, false, true),
                "a death after the order was cleared is still a death, and still the last row");
    }

    @Test
    void clearingAnOrderReturnsThemToCustodyRatherThanToFreedom() {
        // Pardon, commutation, rescue, escape, guard death, device destruction and chunk unload all
        // clear the authorisation, which is this transition and no other.
        CustodyTransitionService.Capital before =
                CustodyTransitionService.capitalState(true, true, false);
        CustodyTransitionService.Capital after =
                CustodyTransitionService.capitalState(true, false, false);
        assertEquals(CustodyTransitionService.Capital.PENDING_EXECUTION, before);
        assertEquals(CustodyTransitionService.Capital.CONDEMNED_IN_CUSTODY, after,
                "never NOT_CAPITAL, and never EXECUTED");
    }

    @Test
    void somebodyWhoIsNotCondemnedIsOnNoCapitalRowAtAll() {
        assertEquals(CustodyTransitionService.Capital.NOT_CAPITAL,
                CustodyTransitionService.capitalState(false, false, false));
        assertEquals(CustodyTransitionService.Capital.NOT_CAPITAL,
                CustodyTransitionService.capitalState(false, true, false),
                "an authorisation for somebody nobody sentenced is not a capital state");
        assertEquals(CustodyTransitionService.Capital.NOT_CAPITAL,
                CustodyTransitionService.capitalState(false, false, true),
                "an ordinary death is an ordinary death");
    }
}
