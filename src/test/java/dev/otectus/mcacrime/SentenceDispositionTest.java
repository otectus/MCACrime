package dev.otectus.mcacrime;

import dev.otectus.mcacrime.jail.ReleaseReason;
import dev.otectus.mcacrime.jail.SentenceDisposition;
import dev.otectus.mcacrime.jail.SentenceDisposition.Effect;
import dev.otectus.mcacrime.ledger.Resolution;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Ending a sentence (0.7.7): which releases settle what, and how much Heat leaves with them.
 *
 * <p>The re-arrest loop this closes: a served sentence closed its cases and left their Heat, so a
 * prisoner still above the Wanted line walked out a legal target; the captivity cap closed nothing, so
 * the same cases put the same player back into the same sentence.
 */
class SentenceDispositionTest {

    @Test
    void everyReleaseHasADisposition() {
        assertEquals(Effect.SERVE, SentenceDisposition.effectOf(ReleaseReason.SENTENCE_SERVED));
        assertEquals(Effect.SERVE, SentenceDisposition.effectOf(ReleaseReason.CAPTIVITY_CAP),
                "the safety cap is time served, or it would loop");
        assertEquals(Effect.SETTLE, SentenceDisposition.effectOf(ReleaseReason.BAILED));
        assertEquals(Effect.SETTLE, SentenceDisposition.effectOf(ReleaseReason.PARDON));
        assertEquals(Effect.UNBIND, SentenceDisposition.effectOf(ReleaseReason.ADMIN), "an operator forgives nothing");
        assertEquals(Effect.UNBIND, SentenceDisposition.effectOf(ReleaseReason.INVALID_JAIL));
        assertEquals(Effect.NONE, SentenceDisposition.effectOf(ReleaseReason.EXECUTED));
        for (ReleaseReason reason : ReleaseReason.values()) {
            SentenceDisposition.effectOf(reason); // exhaustive: a new reason must be given a disposition
        }
    }

    @Test
    void settlementIsTheObligationAndNeverMoreThanIsLeft() {
        assertEquals(40L, SentenceDisposition.settlement(40L, 150L), "Heat from crimes in custody survives");
        assertEquals(30L, SentenceDisposition.settlement(40L, 30L), "decay already took the rest");
        assertEquals(0L, SentenceDisposition.settlement(0L, 150L), "a fully decayed obligation settles nothing");
        assertEquals(0L, SentenceDisposition.settlement(40L, 0L));
        assertEquals(0L, SentenceDisposition.settlement(-1L, 150L), "an unknown obligation is resolved first");
    }

    @Test
    void aLegacySentenceAnswersForItsOpenCasesOrForHeatAlone() {
        assertEquals(30L, SentenceDisposition.legacyObligation(100L, List.of(10L, 20L)));
        assertEquals(50L, SentenceDisposition.legacyObligation(50L, List.of(40L, 40L)), "capped by the Heat left");
        assertEquals(100L, SentenceDisposition.legacyObligation(100L, List.of()), "a Heat-only sentence");
        assertEquals(0L, SentenceDisposition.legacyObligation(-5L, List.of(10L)));
    }

    @Test
    void aSentenceEndsEarlyOnlyWhenEveryChargeWasPardonedOrPaid() {
        assertEquals(ReleaseReason.PARDON, SentenceDisposition.resolvedBy(List.of(Resolution.PARDONED)));
        assertEquals(ReleaseReason.PARDON, SentenceDisposition.resolvedBy(List.of(Resolution.PARDONED, Resolution.FINED)));
        assertEquals(ReleaseReason.BAILED, SentenceDisposition.resolvedBy(List.of(Resolution.FINED, Resolution.FINED)));
        assertNull(SentenceDisposition.resolvedBy(List.of(Resolution.PARDONED, Resolution.UNRESOLVED)),
                "a charge still open keeps the sentence running");
        assertNull(SentenceDisposition.resolvedBy(List.of(Resolution.PARDONED, Resolution.ESCAPED)),
                "an escaped charge is still actionable");
        assertNull(SentenceDisposition.resolvedBy(List.of(Resolution.EXPIRED)), "expiry is not the law letting go");
        assertNull(SentenceDisposition.resolvedBy(List.of(Resolution.SERVED)), "served is this sentence's own end");
        assertNull(SentenceDisposition.resolvedBy(List.of()), "a sentence binding no charge runs its time");
    }
}
