package dev.otectus.mcacrime;

import dev.otectus.mcacrime.jail.JailContainmentMode;
import dev.otectus.mcacrime.jail.JailService;
import dev.otectus.mcacrime.jail.JailState;
import dev.otectus.mcacrime.jail.ReleaseReason;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

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
    void notTickingDoesNotDecrement() {
        // The decay handler only calls advanceTick while the player is online -> a logged-out player's
        // remaining ticks are frozen. Modelled here by simply not advancing.
        JailState j = jail(100);
        assertEquals(100L, j.getRemainingOnlineTicks());
    }
}
