package dev.otectus.mcacrime.network;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The rate limit in front of the five server-bound payloads (T44).
 *
 * <p>The clock is substituted rather than slept on: the bucket refills per tick, so a test that waited
 * for real time would take ten seconds to prove what an advanced counter proves immediately.
 */
class RequestBudgetTest {

    private final AtomicLong now = new AtomicLong();

    @AfterEach
    void restoreClock() {
        RequestBudget.reset();
    }

    private void useClock() {
        RequestBudget.setClock(now::get);
    }

    @Test
    void aBurstIsAllowedInFullAndThenRefused() {
        useClock();
        UUID player = UUID.randomUUID();
        for (int i = 0; i < RequestBudget.Category.MENU.burst(); i++) {
            assertTrue(RequestBudget.allow(player, RequestBudget.Category.MENU), "token " + i);
        }
        assertFalse(RequestBudget.allow(player, RequestBudget.Category.MENU));
        // A refusal spends nothing, so a spammer never holds their own bucket below empty.
        assertFalse(RequestBudget.allow(player, RequestBudget.Category.MENU));
    }

    @Test
    void categoriesAreBudgetedSeparately() {
        useClock();
        UUID player = UUID.randomUUID();
        for (int i = 0; i < RequestBudget.Category.MENU.burst(); i++) {
            assertTrue(RequestBudget.allow(player, RequestBudget.Category.MENU));
        }
        assertFalse(RequestBudget.allow(player, RequestBudget.Category.MENU));
        assertTrue(RequestBudget.allow(player, RequestBudget.Category.ACTION));
    }

    @Test
    void playersAreBudgetedSeparately() {
        useClock();
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        for (int i = 0; i < RequestBudget.Category.CHALLENGE.burst(); i++) {
            assertTrue(RequestBudget.allow(first, RequestBudget.Category.CHALLENGE));
        }
        assertFalse(RequestBudget.allow(first, RequestBudget.Category.CHALLENGE));
        assertTrue(RequestBudget.allow(second, RequestBudget.Category.CHALLENGE));
    }

    @Test
    void theDossierRefillsExactlyOneTokenPerTenTicks() {
        useClock();
        UUID player = UUID.randomUUID();
        assertTrue(RequestBudget.allow(player, RequestBudget.Category.DOSSIER));
        assertFalse(RequestBudget.allow(player, RequestBudget.Category.DOSSIER));

        now.addAndGet(9L);
        assertFalse(RequestBudget.allow(player, RequestBudget.Category.DOSSIER), "nine ticks is not a token");

        now.addAndGet(1L);
        assertTrue(RequestBudget.allow(player, RequestBudget.Category.DOSSIER));
        assertFalse(RequestBudget.allow(player, RequestBudget.Category.DOSSIER), "and only the one token");
    }

    @Test
    void refillIsCappedAtTheBurst() {
        useClock();
        UUID player = UUID.randomUUID();
        for (int i = 0; i < RequestBudget.Category.ACTION.burst(); i++) {
            assertTrue(RequestBudget.allow(player, RequestBudget.Category.ACTION));
        }
        now.addAndGet(20_000L); // idle for a long time
        for (int i = 0; i < RequestBudget.Category.ACTION.burst(); i++) {
            assertTrue(RequestBudget.allow(player, RequestBudget.Category.ACTION), "refilled token " + i);
        }
        assertFalse(RequestBudget.allow(player, RequestBudget.Category.ACTION), "idling does not bank credit");
    }

    @Test
    void aClockThatGoesBackwardsHandsOutNoCredit() {
        useClock();
        UUID player = UUID.randomUUID();
        now.set(10_000L);
        for (int i = 0; i < RequestBudget.Category.MENU.burst(); i++) {
            assertTrue(RequestBudget.allow(player, RequestBudget.Category.MENU));
        }
        now.set(0L);
        assertFalse(RequestBudget.allow(player, RequestBudget.Category.MENU));
    }

    @Test
    void forgetDropsEveryBucketForThatPlayerAlone() {
        useClock();
        UUID player = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        assertTrue(RequestBudget.allow(player, RequestBudget.Category.MENU));
        assertTrue(RequestBudget.allow(player, RequestBudget.Category.DOSSIER));
        assertTrue(RequestBudget.allow(other, RequestBudget.Category.MENU));
        assertTrue(RequestBudget.tracked(player));

        RequestBudget.forget(player);
        assertFalse(RequestBudget.tracked(player));
        assertTrue(RequestBudget.tracked(other));
    }

    @Test
    void aNullPlayerOrCategoryIsRefusedRatherThanTracked() {
        useClock();
        assertFalse(RequestBudget.allow(null, RequestBudget.Category.MENU));
        assertFalse(RequestBudget.allow(UUID.randomUUID(), null));
        assertFalse(RequestBudget.tracked(null));
    }
}
