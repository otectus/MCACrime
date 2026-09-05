package dev.otectus.mcacrime;

import dev.otectus.mcacrime.crime.Band;
import dev.otectus.mcacrime.enforcement.LegalBasis;
import dev.otectus.mcacrime.enforcement.LegalTarget;
import dev.otectus.mcacrime.enforcement.OutlawResolver;
import dev.otectus.mcacrime.enforcement.OutlawStatus;
import dev.otectus.mcacrime.ledger.Warrant;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The spec's outlaw-legality regression, written the way the spec asks for it.
 *
 * <p>The headline invariant is <em>compared</em>, not restated: every row asserts that the resolver's
 * {@code lawfulCombatTarget} equals what {@code LegalTarget.isLegalTarget} says for the same inputs.
 * A table of hand-written expected booleans would be a second implementation of the truth table and
 * would drift away from the first one exactly when somebody changed it — which is how a hunter ends
 * up charged with assault for force the law authorised.
 */
class OutlawResolverTest {

    private static final UUID OFFENDER = UUID.fromString("00000000-0000-0000-0000-0000000000aa");
    private static final ResourceLocation OFFENSE = ResourceLocation.fromNamespaceAndPath("mcacrime", "assault");

    private static Warrant openWarrant() {
        return Warrant.open(UUID.randomUUID(), OFFENDER, OFFENSE, UUID.randomUUID(), 100L);
    }

    private static OutlawStatus resolve(boolean wanted, Band band, boolean redIsLegalTarget,
                                        boolean escaped, boolean holdingCaptive, boolean resisting,
                                        Warrant warrant, boolean redBandBounty) {
        return OutlawResolver.evaluate(wanted, band, redIsLegalTarget, true, escaped, holdingCaptive,
                resisting, 0L, 0L, warrant, true, redBandBounty);
    }

    /** One row of the spec's matrix. Only the inputs are stated; the expectation is computed, not typed. */
    private record Row(String name, boolean wanted, Band band, boolean redIsLegalTarget,
                       boolean escaped, boolean holdingCaptive, boolean resisting) {
    }

    /**
     * The matrix, as a plain loop rather than a JUnit parameterized test: {@code junit-jupiter-params}
     * is not on this project's test classpath, and one assertion repeated over a table needs no
     * framework support to be readable.
     */
    @Test
    void combatTargetAlwaysAgreesWithLegalTarget() {
        Row[] rows = {
                new Row("lawful and cold", false, Band.GREY, false, false, false, false),
                new Row("wanted outlaw", true, Band.GREY, false, false, false, false),
                new Row("red karma, config says legal target", false, Band.RED, true, false, false, false),
                new Row("red karma, redIsLegalTarget off", false, Band.RED, false, false, false, false),
                new Row("escaped prisoner", false, Band.GREY, false, true, false, false),
                new Row("active kidnapper", false, Band.BLUE, false, false, true, false),
                new Row("refused a lawful challenge", false, Band.BLUE, false, false, false, true),
                new Row("everything at once", true, Band.RED, true, true, true, true),
        };
        for (Row row : rows) {
            OutlawStatus status = resolve(row.wanted(), row.band(), row.redIsLegalTarget(), row.escaped(),
                    row.holdingCaptive(), row.resisting(), null, false);
            assertEquals(
                    LegalTarget.isLegalTarget(row.wanted(), row.band(), row.redIsLegalTarget(), row.escaped(),
                            row.holdingCaptive(), row.resisting()),
                    status.lawfulCombatTarget(),
                    "the resolver holds a second opinion about who may be attacked: " + row.name());
        }
    }

    @Test
    void lethalForceNeedsMoreThanAReputation() {
        // Red and nothing else: attackable when the server says so, killable only when it says so twice.
        OutlawStatus killingAllowed = OutlawResolver.evaluate(false, Band.RED, true, true, false, false, false,
                0L, 0L, null, true, false);
        assertTrue(killingAllowed.lawfulCombatTarget());
        assertTrue(killingAllowed.lethalForceLawful());

        OutlawStatus killingForbidden = OutlawResolver.evaluate(false, Band.RED, true, false, false, false, false,
                0L, 0L, null, true, false);
        assertTrue(killingForbidden.lawfulCombatTarget());
        assertFalse(killingForbidden.lethalForceLawful(), "subduing an outlaw is not executing one");
    }

    @Test
    void aWantedPlayerMayBeKilledEvenWhenRedKillingIsOff() {
        OutlawStatus status = OutlawResolver.evaluate(true, Band.RED, true, false, false, false, false,
                0L, 0L, null, true, false);
        assertTrue(status.lethalForceLawful());
    }

    @Test
    void basisNamesTheMostRecentThingTheyDid() {
        assertEquals(LegalBasis.RESISTING_ARREST,
                resolve(true, Band.RED, true, true, true, true, null, false).basis());
        assertEquals(LegalBasis.WANTED, resolve(true, Band.GREY, false, false, false, false, null, false).basis());
        assertEquals(LegalBasis.RED_BAND, resolve(false, Band.RED, true, false, false, false, null, false).basis());
        assertEquals(LegalBasis.NONE, resolve(false, Band.RED, false, false, false, false, null, false).basis());
    }

    @Test
    void noWarrantMeansNoBounty() {
        OutlawStatus status = resolve(true, Band.RED, true, false, false, false, null, true);
        assertTrue(status.lawfulCombatTarget());
        assertFalse(status.bountyEligible(), "being attackable is not the same as being worth money");
    }

    @Test
    void aClosedWarrantPaysNothing() {
        assertFalse(resolve(true, Band.GREY, false, false, false, false, openWarrant().closed(200L), false)
                .bountyEligible());
    }

    @Test
    void anOpenWarrantOnAWantedPlayerIsEligible() {
        Warrant warrant = openWarrant();
        OutlawStatus status = resolve(true, Band.GREY, false, false, false, false, warrant, false);
        assertTrue(status.bountyEligible());
        assertEquals(warrant.id(), status.warrantId());
        assertEquals(1L, status.warrantRevision());
    }

    @Test
    void redBandEligibilityFollowsItsOwnFlag() {
        Warrant warrant = openWarrant();
        assertFalse(resolve(false, Band.RED, true, false, false, false, warrant, false).bountyEligible());
        assertTrue(resolve(false, Band.RED, true, false, false, false, warrant, true).bountyEligible());
    }

    @Test
    void anOutlawWhoStoppedBeingOneBeforeTheBlowIsNoLongerALawfulTarget() {
        // The spec's last matrix row, and the one the timing policy turns on: the warrant is still on
        // file, closed, and the Heat has gone. Attacking them is assault again, and worth nothing.
        Warrant expired = openWarrant().closed(200L);
        OutlawStatus status = resolve(false, Band.GREY, false, false, false, false, expired, true);
        assertFalse(status.lawfulCombatTarget());
        assertFalse(status.lethalForceLawful());
        assertFalse(status.bountyEligible());
        assertEquals(LegalBasis.NONE, status.basis());
    }

    @Test
    void anOpenWarrantOnSomebodyWithNoQualifyingBasisPaysNothing() {
        // A warrant left open by a Heat decay race is not a licence: eligibility needs the basis too,
        // and the two facts are deliberately independent.
        OutlawStatus status = resolve(false, Band.GREY, false, false, false, false, openWarrant(), true);
        assertFalse(status.bountyEligible());
        assertFalse(status.lawfulCombatTarget());
    }

    @Test
    void lawfulForceAndBountyEligibilityAreIndependent() {
        // A kidnapper may be stopped by anybody; that does not make them a payday. The spec is explicit
        // that "temporarily attackable" and "carries a government bounty" are different questions.
        OutlawStatus kidnapper = resolve(false, Band.BLUE, false, false, true, false, openWarrant(), true);
        assertTrue(kidnapper.lawfulCombatTarget());
        assertFalse(kidnapper.bountyEligible());
    }

    @Test
    void aRevisedWarrantIsReportedAtItsNewRevision() {
        Warrant revised = openWarrant().revised(UUID.randomUUID(), OFFENSE, 300L);
        OutlawStatus status = resolve(true, Band.GREY, false, false, false, false, revised, false);
        assertTrue(status.bountyEligible());
        assertEquals(2L, status.warrantRevision(), "the claim key has to follow the offence");
    }

    @Test
    void bountiesTurnedOffPayNothingHoweverWantedTheyAre() {
        OutlawStatus status = OutlawResolver.evaluate(true, Band.RED, true, true, false, false, false,
                0L, 0L, openWarrant(), false, true);
        assertFalse(status.bountyEligible());
    }
}
