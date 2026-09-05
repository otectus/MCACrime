package dev.otectus.mcacrime;

import dev.otectus.mcacrime.enforcement.GuardChallengeService;
import dev.otectus.mcacrime.ledger.CrimeFlag;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.OptionalLong;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A thief caught in the act cannot buy their way out (spec §"Guards and thief arrests").
 *
 * <p>The fine-versus-jail decision has exactly one fork in the whole mod, and this is it. Until
 * 0.5.1 it read "fines are enabled and an amount could be calculated", which is a statement about
 * money and says nothing about what was done. {@link CrimeFlag#MANDATORY_CUSTODY} is the third term,
 * and it overrides both: an offence that is not fine-payable ends in custody or not at all.
 */
class MandatoryCustodyTest {

    private static final OptionalLong FINE = OptionalLong.of(12L);

    @Test
    void anOrdinaryChargeWithFinesOnIsFinable() {
        assertTrue(GuardChallengeService.finable(true, FINE, EnumSet.noneOf(CrimeFlag.class)));
    }

    @Test
    void mandatoryCustodyForcesFalseEvenWithAnAmountAndFinesOn() {
        assertFalse(GuardChallengeService.finable(true, FINE, EnumSet.of(CrimeFlag.MANDATORY_CUSTODY)));
    }

    @Test
    void mandatoryCustodyAlongsideOtherFlagsStillForcesFalse() {
        assertFalse(GuardChallengeService.finable(true, FINE,
                EnumSet.of(CrimeFlag.CAUGHT_IN_ACT, CrimeFlag.NPC_OFFENDER, CrimeFlag.MANDATORY_CUSTODY)));
    }

    @Test
    void theOtherFlagsAloneDoNotCloseTheFineBranch() {
        assertTrue(GuardChallengeService.finable(true, FINE,
                EnumSet.of(CrimeFlag.CAUGHT_IN_ACT, CrimeFlag.NPC_OFFENDER)));
    }

    @Test
    void finesOffOrNoAmountAreStillTheOriginalTerms() {
        assertFalse(GuardChallengeService.finable(false, FINE, EnumSet.noneOf(CrimeFlag.class)));
        assertFalse(GuardChallengeService.finable(true, OptionalLong.empty(), EnumSet.noneOf(CrimeFlag.class)));
    }

    @Test
    void aNullFlagSetIsTreatedAsNoFlags() {
        Set<CrimeFlag> none = null;
        assertTrue(GuardChallengeService.finable(true, FINE, none));
    }

    @Test
    void theFlagSurvivesTheContextEncodingItIsCarriedIn() {
        // The flag reaches the fork through the record's context map, so the round-trip is part of
        // the rule rather than a detail of the ledger.
        String encoded = CrimeFlag.encode(EnumSet.of(CrimeFlag.CAUGHT_IN_ACT, CrimeFlag.MANDATORY_CUSTODY));
        assertFalse(GuardChallengeService.finable(true, FINE, CrimeFlag.decode(encoded)));
    }
}
