package dev.otectus.mcacrime.ledger;

import dev.otectus.mcacrime.captivity.CustodyReleaseReason;
import dev.otectus.mcacrime.jail.ReleaseReason;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The settlement behind a carried-out sentence happens once (0.7.5 §3.19, acceptance 21.6).
 *
 * <p>"Once" is the only interesting property here, and it is the one a live test could not establish
 * reliably: a guillotine can be reloaded mid-swing, a damage event can be re-fired by another mod
 * and a chunk can unload between the blade and the death. {@link CapitalDeathOutcome#claim} is the
 * single gate all of that goes through, so it is asserted directly.
 *
 * <p>A <b>cancelled</b> death — a totem, PlayerRevive, an invulnerable target, another mod's damage
 * handler — never reaches the settlement at all: the device confirms the subject is dead before it
 * calls, and this class is not on any other path. That is structural, and the case that proves it
 * lives in {@code GuillotineExecutionTest::cancelledDeathTotemAndRevive}.
 */
class CapitalDeathOutcomeTest {

    @BeforeEach
    @AfterEach
    void forget() {
        CapitalDeathOutcome.clearAll();
    }

    @Test
    void casesWarrantsAndBountiesCloseOnce() {
        UUID sentence = UUID.randomUUID();

        assertTrue(CapitalDeathOutcome.claim(sentence), "the first blow settles the sentence");
        assertFalse(CapitalDeathOutcome.claim(sentence),
                "a replayed blow must settle nothing twice: no second case closure, no second payout, "
                        + "no second estate");
        assertTrue(CapitalDeathOutcome.settled(sentence));
    }

    @Test
    void twoSentencesSettleIndependently() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();

        assertTrue(CapitalDeathOutcome.claim(first));
        assertTrue(CapitalDeathOutcome.claim(second),
                "one prisoner's execution must not consume another's settlement");
        assertTrue(CapitalDeathOutcome.settled(first));
        assertTrue(CapitalDeathOutcome.settled(second));
    }

    @Test
    void nothingIsSettledForNoSentence() {
        assertFalse(CapitalDeathOutcome.claim(null));
        assertFalse(CapitalDeathOutcome.settled(null));
    }

    @Test
    void theCompletionSetIsForgettableForATestAndAServerStop() {
        UUID sentence = UUID.randomUUID();
        CapitalDeathOutcome.claim(sentence);
        CapitalDeathOutcome.clearAll();
        assertFalse(CapitalDeathOutcome.settled(sentence));
    }

    /** The paired reasons §3.19 names: one on the jail side, one on the custody side. */
    @Test
    void theExecutedReasonExistsOnBothSidesOfTheBridge() {
        assertNotNull(ReleaseReason.valueOf("EXECUTED"));
        assertNotNull(CustodyReleaseReason.valueOf("CAPTIVE_DIED"));
        assertEquals("executed", ReleaseReason.EXECUTED.name().toLowerCase(java.util.Locale.ROOT));
    }

    @Test
    void aResultDescribesWhatItDid() {
        CapitalDeathOutcome.Result nothing = new CapitalDeathOutcome.Result(false, 0, false, 0, false);
        assertFalse(nothing.ran());
        CapitalDeathOutcome.Result ran = new CapitalDeathOutcome.Result(true, 2, true, 3, false);
        assertTrue(ran.ran());
        assertEquals(2, ran.casesClosed());
        assertEquals(3, ran.lotsDropped());
        assertFalse(ran.possessionsBanked(), "dropped and banked are the two halves of one switch");
    }
}
