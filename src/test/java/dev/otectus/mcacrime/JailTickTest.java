package dev.otectus.mcacrime;

import dev.otectus.mcacrime.jail.JailContainmentMode;
import dev.otectus.mcacrime.jail.JailService;
import dev.otectus.mcacrime.jail.JailState;
import dev.otectus.mcacrime.jail.ReleaseReason;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Online-tick jail accounting (spec §7.1, §7.2): countdown, the captivity-cap backstop, and one-tick semantics. */
class JailTickTest {

    private static JailState jail(long remaining) {
        return new JailState(remaining, null, null, 0, JailContainmentMode.CONTAINMENT);
    }

    @Test
    void sentenceServedWhenItReachesZero() {
        JailState j = jail(1);
        assertEquals(ReleaseReason.SENTENCE_SERVED, JailService.advanceTick(j, 1_000_000L));
        assertEquals(0L, j.getRemainingOnlineTicks());
    }

    @Test
    void eachTickDecrementsExactlyOne() {
        JailState j = jail(5);
        assertNull(JailService.advanceTick(j, 1_000_000L)); // 5 -> 4
        assertNull(JailService.advanceTick(j, 1_000_000L)); // 4 -> 3
        assertEquals(3L, j.getRemainingOnlineTicks());
        assertEquals(2L, j.getRealOnlineTicksServed());
    }

    @Test
    void captivityCapForcesReleaseBeforeAHugeSentence() {
        JailState j = jail(1_000_000L);
        assertNull(JailService.advanceTick(j, 3L)); // served 1
        assertNull(JailService.advanceTick(j, 3L)); // served 2
        assertEquals(ReleaseReason.CAPTIVITY_CAP, JailService.advanceTick(j, 3L)); // served 3 >= cap
    }

    @Test
    void resyncFiresOnTheIntervalAndNeverDividesByZero() {
        assertTrue(JailService.shouldResync(40L, 40));
        assertTrue(JailService.shouldResync(0L, 40));
        assertFalse(JailService.shouldResync(41L, 40));
        assertFalse(JailService.shouldResync(39L, 40));
        // A disabled interval must not throw; it simply never resyncs.
        assertFalse(JailService.shouldResync(0L, 0));
        assertFalse(JailService.shouldResync(40L, -1));
    }

    @Test
    void aThousandTickSentenceCostsTwentyFiveResyncs() {
        int resyncs = 0;
        for (long remaining = 999L; remaining >= 0L; remaining--) {
            if (JailService.shouldResync(remaining, 40)) {
                resyncs++;
            }
        }
        assertEquals(25, resyncs);
    }

    @Test
    void notTickingDoesNotDecrement() {
        // The decay handler only calls advanceTick while the player is online -> a logged-out player's
        // remaining ticks are frozen. Modelled here by simply not advancing.
        JailState j = jail(100);
        assertEquals(100L, j.getRemainingOnlineTicks());
    }

    // ---------------------------------------------------------------- sentence merging

    /**
     * The asymmetry is the design. A surrender may shorten but never lengthen; everything else may
     * lengthen but never shorten. Either rule applied in both directions is a way to game a sentence.
     */
    @Test
    void aSurrenderMayShortenASentenceButNeverLengthenIt() {
        assertEquals(750L, JailService.mergeSentence(1000L, 750L, true));
        assertEquals(500L, JailService.mergeSentence(500L, 750L, true),
                "surrendering again must not add time");
        assertEquals(1L, JailService.mergeSentence(1L, 0L, true), "never below a single tick");
    }

    @Test
    void everyOtherCallerMayLengthenButNeverShorten() {
        assertEquals(1000L, JailService.mergeSentence(1000L, 750L, false));
        assertEquals(750L, JailService.mergeSentence(500L, 750L, false));
    }
}
