package dev.otectus.mcacrime;

import dev.otectus.mcacrime.enforcement.BailQuote;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Paying bail, in the order the money and the release have to happen in.
 *
 * <p>Every case here is one somebody loses emeralds to if it is wrong. A player who cannot afford it
 * must not be charged; a sentence that ended between the quote and the click must not be charged for;
 * a currency that refuses at the last moment must not produce a release; and a duplicated packet must
 * not charge twice. The last one falls out of checking custody first — the release removed the record,
 * so the second attempt finds nobody to bail.
 */
class BailPaymentFlowTest {

    /** A recording stand-in for the world: a balance, a charge log, and a release counter. */
    private static final class Till implements BailQuote.Payment {

        private long balance;
        private boolean held = true;
        private long charged;
        private int charges;
        private int releases;
        private boolean chargeSucceeds = true;

        Till(long balance) {
            this.balance = balance;
        }

        @Override
        public boolean inCustody() {
            return held;
        }

        @Override
        public long balance() {
            return balance;
        }

        @Override
        public boolean charge(long cost) {
            charges++;
            if (!chargeSucceeds) {
                return false;
            }
            charged += cost;
            balance -= cost;
            return true;
        }

        @Override
        public void release() {
            releases++;
            held = false;
        }
    }

    @Test
    void theFirstClickQuotesAndChargesNothing() {
        Till till = new Till(1000L);
        assertEquals(BailQuote.Outcome.QUOTED, BailQuote.pay(till, 112L, false));
        assertEquals(0, till.charges);
        assertEquals(0, till.releases);
    }

    @Test
    void anEmptyPurseIsRefusedBeforeAnythingIsTaken() {
        Till till = new Till(111L);
        assertEquals(BailQuote.Outcome.INSUFFICIENT, BailQuote.pay(till, 112L, true));
        assertEquals(0, till.charges);
        assertEquals(0, till.releases);
        assertEquals(111L, till.balance);
    }

    @Test
    void exactlyEnoughIsEnough() {
        Till till = new Till(112L);
        assertEquals(BailQuote.Outcome.PAID, BailQuote.pay(till, 112L, true));
        assertEquals(112L, till.charged);
        assertEquals(1, till.releases);
    }

    @Test
    void aCurrencyThatRefusesAtTheLastMomentDoesNotFreeAnybody() {
        Till till = new Till(1000L);
        till.chargeSucceeds = false;
        assertEquals(BailQuote.Outcome.INSUFFICIENT, BailQuote.pay(till, 112L, true));
        assertEquals(1, till.charges);
        assertEquals(0, till.releases);
    }

    @Test
    void aSentenceThatEndedFirstCostsNothing() {
        Till till = new Till(1000L);
        till.held = false;
        assertEquals(BailQuote.Outcome.ALREADY_RELEASED, BailQuote.pay(till, 112L, true));
        assertEquals(0, till.charges);
        assertEquals(1000L, till.balance);
    }

    @Test
    void aDuplicatedPacketChargesOnceAndReleasesOnce() {
        Till till = new Till(1000L);
        assertEquals(BailQuote.Outcome.PAID, BailQuote.pay(till, 112L, true));
        assertEquals(BailQuote.Outcome.ALREADY_RELEASED, BailQuote.pay(till, 112L, true));
        assertEquals(1, till.charges);
        assertEquals(1, till.releases);
        assertEquals(888L, till.balance);
    }
}
