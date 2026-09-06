package dev.otectus.mcacrime;

import dev.otectus.mcacrime.network.RequestBudget;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The token bucket, on a clock the test owns.
 *
 * <p>Substituting the clock rather than sleeping is not only about speed: a sleeping test asserts
 * "roughly a second passed", and the property that matters here is exact — a refused request must
 * leave the bucket alone, or a player being rate-limited holds themselves at zero by retrying and
 * never recovers.
 */
class RequestBudgetTest {

    private final AtomicLong tick = new AtomicLong();

    @BeforeEach
    void useTestClock() {
        tick.set(0L);
        RequestBudget.reset();
        RequestBudget.setClock(tick::get);
    }

    @AfterEach
    void restoreClock() {
        RequestBudget.reset();
    }

    @Test
    void theBurstIsHonouredAndThenTheBucketIsEmpty() {
        UUID player = UUID.randomUUID();
        for (int i = 0; i < RequestBudget.Category.MENU.burst(); i++) {
            assertTrue(RequestBudget.allow(player, RequestBudget.Category.MENU), "burst request " + i);
        }
        assertFalse(RequestBudget.allow(player, RequestBudget.Category.MENU));
    }

    @Test
    void aRefusedRequestConsumesNothing() {
        UUID player = UUID.randomUUID();
        while (RequestBudget.allow(player, RequestBudget.Category.MENU)) {
            // drain
        }
        for (int i = 0; i < 20; i++) {
            assertFalse(RequestBudget.allow(player, RequestBudget.Category.MENU));
        }
        // MENU refills at 4/s, so five ticks buy exactly one request back — no more, and no fewer for
        // the twenty refusals above.
        tick.addAndGet(5L);
        assertTrue(RequestBudget.allow(player, RequestBudget.Category.MENU));
        assertFalse(RequestBudget.allow(player, RequestBudget.Category.MENU));
    }

    @Test
    void theBucketRefillsAtTheConfiguredRateAndStopsAtTheBurst() {
        UUID player = UUID.randomUUID();
        while (RequestBudget.allow(player, RequestBudget.Category.ACTION)) {
            // drain
        }
        tick.addAndGet(1000L);
        for (int i = 0; i < RequestBudget.Category.ACTION.burst(); i++) {
            assertTrue(RequestBudget.allow(player, RequestBudget.Category.ACTION), "refilled request " + i);
        }
        assertFalse(RequestBudget.allow(player, RequestBudget.Category.ACTION),
                "a long idle period must not bank more than one burst");
    }

    @Test
    void theDossierIsOnePerTenTicks() {
        UUID player = UUID.randomUUID();
        assertTrue(RequestBudget.allow(player, RequestBudget.Category.DOSSIER));
        assertFalse(RequestBudget.allow(player, RequestBudget.Category.DOSSIER));
        tick.addAndGet(9L);
        assertFalse(RequestBudget.allow(player, RequestBudget.Category.DOSSIER));
        tick.addAndGet(1L);
        assertTrue(RequestBudget.allow(player, RequestBudget.Category.DOSSIER));
    }

    @Test
    void categoriesDoNotShareABucket() {
        UUID player = UUID.randomUUID();
        while (RequestBudget.allow(player, RequestBudget.Category.MENU)) {
            // drain
        }
        assertTrue(RequestBudget.allow(player, RequestBudget.Category.CHALLENGE));
    }

    @Test
    void logoutDropsThePlayersEntries() {
        UUID player = UUID.randomUUID();
        assertTrue(RequestBudget.allow(player, RequestBudget.Category.MENU));
        assertTrue(RequestBudget.allow(player, RequestBudget.Category.ACTION));
        assertTrue(RequestBudget.tracked(player));
        RequestBudget.forget(player);
        assertFalse(RequestBudget.tracked(player));
    }
}
