package dev.otectus.mcacrime;

import dev.otectus.mcacrime.job.OccupationSource;
import dev.otectus.mcacrime.job.RecruitmentEligibility;
import dev.otectus.mcacrime.job.RecruitmentEligibility.Facts;
import dev.otectus.mcacrime.job.RecruitmentEligibility.Verdict;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Who an automatic route may still turn into a criminal (0.7.7).
 *
 * <p>The report this pins: a master librarian in a player's trading hall has no MCA home village, so
 * the wild route took it, and the occupation transaction cleared every trade it had. The rule is that
 * only a blank slate qualifies, and each way of not being one is asserted on its own so a regression
 * names the fact it stopped checking.
 */
class RecruitmentEligibilityTest {

    /** A readable {@code none} profession, no XP, level one, no used offer. */
    private static Facts blank() {
        return new Facts(true, true, false, 0, 1, false);
    }

    @Test
    void anUnemployedVillagerWithNoHistoryIsEligible() {
        assertEquals(Verdict.ELIGIBLE, RecruitmentEligibility.evaluate(blank()));
        assertTrue(RecruitmentEligibility.evaluate(blank()).eligible());
    }

    @Test
    void aMasterLibrarianWithTradesIsEmployedAndNeverEligible() {
        Facts librarian = new Facts(true, false, false, 250, 5, true);
        assertEquals(Verdict.EMPLOYED, RecruitmentEligibility.evaluate(librarian));
        assertTrue(RecruitmentEligibility.evaluate(librarian).rejected());
    }

    @Test
    void anyTradeHistoryOnAnUnemployedVillagerIsEstablished() {
        assertEquals(Verdict.TRADED, RecruitmentEligibility.evaluate(new Facts(true, true, false, 1, 1, false)),
                "a single point of trading XP is history");
        assertEquals(Verdict.TRADED, RecruitmentEligibility.evaluate(new Facts(true, true, false, 0, 2, false)),
                "a level above one is history");
        assertEquals(Verdict.TRADED, RecruitmentEligibility.evaluate(new Facts(true, true, false, 0, 1, true)),
                "a used offer is history");
    }

    @Test
    void mcaProtectionOutranksEverythingElse() {
        assertEquals(Verdict.PROTECTED, RecruitmentEligibility.evaluate(new Facts(true, true, true, 0, 1, false)));
        assertEquals(Verdict.PROTECTED, RecruitmentEligibility.evaluate(new Facts(false, false, true, 0, 1, false)),
                "protection is reported even when the profession could not be read");
    }

    @Test
    void anUnreadableVillagerFailsClosed() {
        assertEquals(Verdict.UNREADABLE, RecruitmentEligibility.evaluate(Facts.unreadable()));
        assertEquals(Verdict.UNREADABLE, RecruitmentEligibility.evaluate(null));
        assertTrue(RecruitmentEligibility.evaluate(Facts.unreadable()).rejected(),
                "unreadable must never be spent as unemployed");
    }

    @Test
    void onlyTheRoutesNobodyAskedForAreAutomatic() {
        assertTrue(OccupationSource.SETTLEMENT_SWEEP.automatic());
        assertTrue(OccupationSource.STATION_RECRUITMENT.automatic());
        assertTrue(OccupationSource.WILD.automatic(), "the wild route needs no station but is still automatic");
        assertFalse(OccupationSource.OPERATOR.automatic(), "an operator may still convert anybody");
        assertFalse(OccupationSource.API.automatic(), "an API caller keeps today's behaviour");
        assertFalse(OccupationSource.NATIVE_REBIND.automatic());
        assertFalse(OccupationSource.MIGRATION.automatic());
        assertFalse(OccupationSource.UNKNOWN.automatic());
        assertFalse(OccupationSource.WILD.requiresStation(), "automatic is a different question from stations");
    }
}
