package dev.otectus.mcacrime.compat;

import org.junit.jupiter.api.Test;

import java.util.NoSuchElementException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The two rules that keep an absent Townstead from looking like a catastrophe in progress.
 *
 * <p>First: an empty result never yields a value, so nothing downstream can read a default and act on
 * it. Second: a need reading that is not real is never a crisis — a villager whose needs Townstead is
 * not tracking has hunger 0, and hunger 0 also happens to be starvation.
 */
class TownsteadQueryResultTest {

    @Test
    void anAvailableResultCarriesItsValue() {
        TownsteadQueryResult<String> result = TownsteadQueryResult.available("yes");

        assertTrue(result.isAvailable());
        assertFalse(result.isFailed());
        assertEquals("yes", result.orThrow());
        assertEquals("yes", result.orElse("fallback"));
        assertEquals("yes", result.asOptional().orElseThrow());
    }

    @Test
    void anAvailableResultMayNotBeEmpty() {
        assertThrows(IllegalArgumentException.class, () -> TownsteadQueryResult.available(null));
    }

    @Test
    void anUnavailableResultNeverYieldsAValue() {
        TownsteadQueryResult<String> result = TownsteadQueryResult.unavailable("Townstead is not installed");

        assertFalse(result.isAvailable());
        assertFalse(result.isFailed());
        assertTrue(result.asOptional().isEmpty());
        assertEquals("fallback", result.orElse("fallback"),
                "the caller's own fallback is the only value an empty result may produce");
        assertThrows(NoSuchElementException.class, result::orThrow);
        assertTrue(result.describe().contains("not installed"), "the reason must survive for diagnostics");
    }

    @Test
    void aMissingCapabilityIsUnavailableAndSaysWhich() {
        TownsteadQueryResult<String> result = TownsteadQueryResult.missing(TownsteadCapability.READ_NEEDS);

        assertFalse(result.isAvailable());
        assertTrue(result.describe().contains(TownsteadCapability.READ_NEEDS.id()));
    }

    @Test
    void aFailureIsDistinctFromAnAbsence() {
        TownsteadQueryResult<String> failed = TownsteadQueryResult.failed("NoClassDefFoundError");

        assertTrue(failed.isFailed());
        assertFalse(failed.isAvailable());
        assertTrue(failed.asOptional().isEmpty());
        assertTrue(failed.describe().startsWith("failed:"),
                "an absent companion and a broken one must not read the same way in a log");
    }

    @Test
    void mappingPreservesTheEmptyCaseAndItsReason() {
        TownsteadQueryResult<String> unavailable = TownsteadQueryResult.unavailable("no state");
        TownsteadQueryResult<Integer> mapped = unavailable.map(String::length);
        assertFalse(mapped.isAvailable());
        assertTrue(mapped.describe().contains("no state"));

        TownsteadQueryResult<Integer> fromFailure =
                TownsteadQueryResult.<String>failed("threw").map(String::length);
        assertTrue(fromFailure.isFailed());

        assertEquals(3, TownsteadQueryResult.available("abc").map(String::length).orThrow());
    }

    /**
     * The zero-is-not-starving rule, in the place it belongs.
     *
     * <p>An untracked reading is what MCA: Crime gets when Townstead is absent, when {@code READ_NEEDS}
     * did not bind, and when the villager's life stage has needs switched off — Townstead pins hunger
     * and thirst for such a stage, and its fatigue recovery gate stays set. Every one of those hands
     * back zeroes, and not one of them means the villager is dying.
     */
    @Test
    void anUntrackedZeroNeedIsNeverStarving() {
        TownsteadNeedsView untracked = TownsteadNeedsView.untracked();

        assertEquals(0, untracked.hunger());
        assertFalse(untracked.tracked());
        assertFalse(untracked.starving(), "zero hunger with no reading behind it is not starvation");
        assertFalse(untracked.parched());
        assertFalse(untracked.exhausted());
        assertFalse(untracked.incapacitated());
        assertFalse(untracked.restRequired());
        assertTrue(untracked.describe().contains("not tracked"));
    }

    /**
     * The same rule for a gated, pinned reading: a needs-gated villager reported with zeroes and the
     * gate set must not read as starving, collapsed-with-hunger, or anything else actionable.
     */
    @Test
    void aGatedZeroNeedIsNotStarving() {
        TownsteadNeedsView gatedZero =
                new TownsteadNeedsView(false, 0, 0f, 0f, 0, 0, 0f, 0, false, true);

        assertTrue(gatedZero.gated());
        assertFalse(gatedZero.starving());
        assertFalse(gatedZero.parched());
        assertFalse(gatedZero.restRequired(),
                "an untracked gate is not an instruction to leave the villager alone either");
    }

    /** A real reading still has to work, or the guard above would be protecting nothing. */
    @Test
    void aRealReadingStillReportsTheTruth() {
        TownsteadNeedsView starving =
                new TownsteadNeedsView(true, 4, 0f, 0f, 2, 0, 0f, 19, true, true);

        assertTrue(starving.starving());
        assertTrue(starving.parched());
        assertTrue(starving.exhausted());
        assertTrue(starving.incapacitated());
        assertTrue(starving.restRequired());

        TownsteadNeedsView comfortable =
                new TownsteadNeedsView(true, 90, 5f, 0f, 18, 5, 0f, 2, false, false);
        assertFalse(comfortable.starving());
        assertFalse(comfortable.parched());
        assertFalse(comfortable.exhausted());
        assertFalse(comfortable.incapacitated());
    }

    /** The schedule view carries the same "is this reading real?" discipline. */
    @Test
    void anUnknownScheduleIsNotAnIdleVillager() {
        TownsteadScheduleView unknown = TownsteadScheduleView.unknown();

        assertFalse(unknown.known());
        assertFalse(unknown.working());
        assertFalse(unknown.resting());
        assertFalse(unknown.nextIsWork());
        assertSame(TownsteadScheduleView.unknown(), TownsteadScheduleView.unknown());
    }

    /**
     * And the life stage: with Townstead's internal stage flags unavailable, a villager must read as a
     * capable participant rather than as a frozen one. Assuming incapacity we cannot confirm would
     * silently switch off witnessing, arrest and dialogue for the whole village.
     */
    @Test
    void anUnreadableStageIsTreatedAsCapable() {
        TownsteadLifeStageView stage =
                TownsteadLifeStageView.withoutFlags("adult", "Adult", 40, 1f, "adult", 18f, 64f);

        assertFalse(stage.flagsKnown());
        assertTrue(stage.mobile());
        assertTrue(stage.needs());
        assertTrue(stage.talkable());
        assertTrue(stage.adult());
        assertTrue(stage.describe().contains("assumed capable"));
    }
}
