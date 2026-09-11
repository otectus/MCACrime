package dev.otectus.mcacrime;

import dev.otectus.mcacrime.relationship.FamilyLoyalty;
import dev.otectus.mcacrime.relationship.FamilyTier;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The loyalty decision: the score arithmetic, and the six hard exclusions that no score can override.
 *
 * <p>Every exclusion is checked <em>alone</em> and at maximum hearts, because the failure that matters
 * is not "the rule is missing" but "the rule is there and a high enough score walks past it".
 */
class FamilyLoyaltyTest {

    private static final FamilyLoyalty.Settings DEFAULTS = new FamilyLoyalty.Settings(
            1.0, 50, 25, 10, 0, Set.of(), Set.of(), 20, 20, 60L);

    /** A spouse with plenty of hearts and nothing standing in the way. */
    private static FamilyLoyalty.Input willing() {
        return new FamilyLoyalty.Input(FamilyTier.SPOUSE, 100, null, 0L,
                false, false, false, true, true);
    }

    @Test
    void scoreIsHeartsPlusTierPlusPersonality() {
        FamilyLoyalty.Input spouse = new FamilyLoyalty.Input(FamilyTier.SPOUSE, 30, null, 0L,
                false, false, false, true, true);

        assertEquals(55, FamilyLoyalty.score(spouse, DEFAULTS));
        assertTrue(FamilyLoyalty.evaluate(spouse, DEFAULTS).loyal());
    }

    @Test
    void heartsWeightScalesTheHeartsHalfOnly() {
        FamilyLoyalty.Settings halved = new FamilyLoyalty.Settings(0.5, 50, 25, 10, 0,
                Set.of(), Set.of(), 20, 20, 60L);
        FamilyLoyalty.Input spouse = new FamilyLoyalty.Input(FamilyTier.SPOUSE, 30, null, 0L,
                false, false, false, true, true);

        assertEquals(40, FamilyLoyalty.score(spouse, halved));
        assertFalse(FamilyLoyalty.evaluate(spouse, halved).loyal());
    }

    @Test
    void eachTierGetsItsOwnBonus() {
        assertEquals(25, tierScore(FamilyTier.SPOUSE));
        assertEquals(10, tierScore(FamilyTier.PARENT));
        assertEquals(10, tierScore(FamilyTier.CHILD));
        assertEquals(10, tierScore(FamilyTier.SIBLING));
        assertEquals(0, tierScore(FamilyTier.EXTENDED));
        assertEquals(0, tierScore(FamilyTier.IN_LAW));
    }

    private static int tierScore(FamilyTier tier) {
        return FamilyLoyalty.score(new FamilyLoyalty.Input(tier, 0, null, 0L,
                false, false, false, true, true), DEFAULTS);
    }

    @Test
    void aLoyalPersonalityAddsAndALawfulOneSubtracts() {
        FamilyLoyalty.Settings settings = new FamilyLoyalty.Settings(1.0, 50, 25, 10, 0,
                Set.of("SHY"), Set.of("STOIC"), 20, 20, 60L);

        assertEquals(45, FamilyLoyalty.score(personality("shy"), settings));
        assertEquals(5, FamilyLoyalty.score(personality("STOIC"), settings));
        // Named in neither list, and named in both, are both "no adjustment".
        assertEquals(25, FamilyLoyalty.score(personality("CURIOUS"), settings));
    }

    @Test
    void aPersonalityInBothListsCancelsOut() {
        FamilyLoyalty.Settings contradictory = new FamilyLoyalty.Settings(1.0, 50, 25, 10, 0,
                Set.of("SHY"), Set.of("SHY"), 20, 20, 60L);

        assertEquals(25, FamilyLoyalty.score(personality("SHY"), contradictory));
    }

    private static FamilyLoyalty.Input personality(String name) {
        return new FamilyLoyalty.Input(FamilyTier.SPOUSE, 0, name, 0L,
                false, false, false, true, true);
    }

    @Test
    void theVictimNeverCoversForTheOffender() {
        FamilyLoyalty.Input input = new FamilyLoyalty.Input(FamilyTier.SPOUSE, 100, null, 0L,
                true, false, false, true, true);

        FamilyLoyalty.Decision decision = FamilyLoyalty.evaluate(input, DEFAULTS);
        assertFalse(decision.loyal());
        assertEquals(FamilyLoyalty.REASON_WITNESS_IS_VICTIM, decision.reasonKey());
    }

    @Test
    void nobodyCoversForACrimeAgainstTheirOwnFamily() {
        FamilyLoyalty.Input input = new FamilyLoyalty.Input(FamilyTier.SPOUSE, 100, null, 0L,
                false, true, false, true, true);

        FamilyLoyalty.Decision decision = FamilyLoyalty.evaluate(input, DEFAULTS);
        assertFalse(decision.loyal());
        assertEquals(FamilyLoyalty.REASON_VICTIM_IS_RELATIVE, decision.reasonKey());
    }

    @Test
    void aGuardIsNeverLoyalToItsOwnFamily() {
        FamilyLoyalty.Input input = new FamilyLoyalty.Input(FamilyTier.SPOUSE, 100, null, 0L,
                false, false, true, true, true);

        FamilyLoyalty.Decision decision = FamilyLoyalty.evaluate(input, DEFAULTS);
        assertFalse(decision.loyal());
        assertEquals(FamilyLoyalty.REASON_RESPONDER, decision.reasonKey());
    }

    @Test
    void aChildIsNotGivenTheChoice() {
        FamilyLoyalty.Input input = new FamilyLoyalty.Input(FamilyTier.SPOUSE, 100, null, 0L,
                false, false, false, false, true);

        FamilyLoyalty.Decision decision = FamilyLoyalty.evaluate(input, DEFAULTS);
        assertFalse(decision.loyal());
        assertEquals(FamilyLoyalty.REASON_NOT_ADULT, decision.reasonKey());
    }

    @Test
    void aTierOutsideTheOperatorsScopeAlwaysReports() {
        FamilyLoyalty.Input input = new FamilyLoyalty.Input(FamilyTier.SPOUSE, 100, null, 0L,
                false, false, false, true, false);

        FamilyLoyalty.Decision decision = FamilyLoyalty.evaluate(input, DEFAULTS);
        assertFalse(decision.loyal());
        assertEquals(FamilyLoyalty.REASON_TIER_OUT_OF_SCOPE, decision.reasonKey());
    }

    @Test
    void heatAboveTheCeilingIsNeverCovered() {
        FamilyLoyalty.Input input = new FamilyLoyalty.Input(FamilyTier.SPOUSE, 100, null, 61L,
                false, false, false, true, true);

        FamilyLoyalty.Decision decision = FamilyLoyalty.evaluate(input, DEFAULTS);
        assertFalse(decision.loyal());
        assertEquals(FamilyLoyalty.REASON_HEAT_TOO_HIGH, decision.reasonKey());
        // Exactly at the ceiling is still covered: the setting is a maximum, not an exclusive bound.
        assertTrue(FamilyLoyalty.evaluate(new FamilyLoyalty.Input(FamilyTier.SPOUSE, 100, null, 60L,
                false, false, false, true, true), DEFAULTS).loyal());
    }

    @Test
    void everyExclusionStillReportsTheScoreThatWouldHaveApplied() {
        FamilyLoyalty.Decision decision = FamilyLoyalty.evaluate(
                new FamilyLoyalty.Input(FamilyTier.SPOUSE, 100, null, 0L,
                        true, false, false, true, true), DEFAULTS);

        assertEquals(125, decision.score());
    }

    @Test
    void belowTheThresholdIsReportedAsSuch() {
        FamilyLoyalty.Input input = new FamilyLoyalty.Input(FamilyTier.EXTENDED, 0, null, 0L,
                false, false, false, true, true);

        FamilyLoyalty.Decision decision = FamilyLoyalty.evaluate(input, DEFAULTS);
        assertFalse(decision.loyal());
        assertEquals(FamilyLoyalty.REASON_BELOW_THRESHOLD, decision.reasonKey());
    }

    @Test
    void aWillingSpouseIsTheBaselineThisSuiteVariesFrom() {
        assertTrue(FamilyLoyalty.evaluate(willing(), DEFAULTS).loyal());
    }
}
