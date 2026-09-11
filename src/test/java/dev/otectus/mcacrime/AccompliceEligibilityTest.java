package dev.otectus.mcacrime;

import dev.otectus.mcacrime.action.handler.AccompliceGate;
import dev.otectus.mcacrime.relationship.FamilyTier;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The gate matrix for "will this relative help you commit a crime?".
 *
 * <p>Every refusal is asserted from an otherwise-eligible relative, one condition at a time, because
 * the failure mode this guards against is not a rule being missing — it is a rule being shadowed by an
 * earlier one and never reached. The hidden/blocked split is asserted too: it decides whether a player
 * is taught a rule or shown an option that names a relationship they do not have.
 */
class AccompliceEligibilityTest {

    /** A willing adult sibling, close enough, off cooldown, at tick 10 000. */
    private static AccompliceGate.Input willing() {
        return new AccompliceGate.Input(true, true, true, true, true, false, false, false, false,
                80, 65, 0L, 6000L, 10_000L);
    }

    @Test
    void aWillingRelativeIsAvailable() {
        assertTrue(AccompliceGate.decide(willing()).available());
    }

    @Test
    void theFeatureBeingOffHidesTheRowRatherThanRefusingIt() {
        AccompliceGate.Input in = new AccompliceGate.Input(false, true, true, true, true, false, false,
                false, false, 100, 65, 0L, 6000L, 10_000L);
        assertEquals(AccompliceGate.Visibility.HIDDEN, AccompliceGate.decide(in).visibility());
    }

    @Test
    void aStrangerIsHiddenAndSoIsEverybodyWhenMcaCannotSayWhoIsFamily() {
        AccompliceGate.Input stranger = new AccompliceGate.Input(true, true, true, false, true, false,
                false, false, false, 100, 65, 0L, 6000L, 10_000L);
        assertEquals(AccompliceGate.Visibility.HIDDEN, AccompliceGate.decide(stranger).visibility());

        AccompliceGate.Input noApi = new AccompliceGate.Input(true, false, true, true, true, false,
                false, false, false, 100, 65, 0L, 6000L, 10_000L);
        assertEquals(AccompliceGate.Visibility.HIDDEN, AccompliceGate.decide(noApi).visibility());

        AccompliceGate.Input notAVillager = new AccompliceGate.Input(true, true, false, true, true, false,
                false, false, false, 100, 65, 0L, 6000L, 10_000L);
        assertEquals(AccompliceGate.Visibility.HIDDEN, AccompliceGate.decide(notAVillager).visibility());
    }

    @Test
    void aChildIsRefusedAndTold() {
        AccompliceGate.Input child = new AccompliceGate.Input(true, true, true, true, false, false, false,
                false, false, 100, 65, 0L, 6000L, 10_000L);
        AccompliceGate.Decision decision = AccompliceGate.decide(child);
        assertEquals(AccompliceGate.Visibility.BLOCKED, decision.visibility());
        assertEquals("mcacrime.accomplice.too_young", decision.reasonKey());
    }

    @Test
    void aRelativeWhoKeepsTheLawRefusesAtAnyNumberOfHearts() {
        AccompliceGate.Input guard = new AccompliceGate.Input(true, true, true, true, true, true, false,
                false, false, 100, 0, 0L, 0L, 10_000L);
        AccompliceGate.Decision decision = AccompliceGate.decide(guard);
        assertEquals(AccompliceGate.Visibility.BLOCKED, decision.visibility());
        assertEquals("mcacrime.accomplice.is_guard", decision.reasonKey());
    }

    @Test
    void custodyAnAnExistingWarrantAndAnActiveJobEachRefuseOnTheirOwn() {
        assertEquals("mcacrime.accomplice.in_custody", AccompliceGate.decide(
                new AccompliceGate.Input(true, true, true, true, true, false, true, false, false,
                        100, 65, 0L, 6000L, 10_000L)).reasonKey());
        assertEquals("mcacrime.accomplice.already_wanted", AccompliceGate.decide(
                new AccompliceGate.Input(true, true, true, true, true, false, false, true, false,
                        100, 65, 0L, 6000L, 10_000L)).reasonKey());
        assertEquals("mcacrime.accomplice.busy", AccompliceGate.decide(
                new AccompliceGate.Input(true, true, true, true, true, false, false, false, true,
                        100, 65, 0L, 6000L, 10_000L)).reasonKey());
    }

    @Test
    void heartsAreCheckedAgainstTheConfiguredThresholdInclusively() {
        AccompliceGate.Input exactly = new AccompliceGate.Input(true, true, true, true, true, false, false,
                false, false, 65, 65, 0L, 6000L, 10_000L);
        assertTrue(AccompliceGate.decide(exactly).available());

        AccompliceGate.Input oneShort = new AccompliceGate.Input(true, true, true, true, true, false, false,
                false, false, 64, 65, 0L, 6000L, 10_000L);
        assertEquals("mcacrime.accomplice.not_close_enough", AccompliceGate.decide(oneShort).reasonKey());
    }

    @Test
    void theRecruitCooldownExpiresExactlyWhenItSaysItDoes() {
        // Helped at 4000, cooldown 6000: refused at 9999, available at 10 000.
        AccompliceGate.Input during = new AccompliceGate.Input(true, true, true, true, true, false, false,
                false, false, 100, 65, 4000L, 6000L, 9999L);
        assertEquals("mcacrime.accomplice.too_soon", AccompliceGate.decide(during).reasonKey());

        AccompliceGate.Input after = new AccompliceGate.Input(true, true, true, true, true, false, false,
                false, false, 100, 65, 4000L, 6000L, 10_000L);
        assertTrue(AccompliceGate.decide(after).available());
    }

    @Test
    void aZeroCooldownNeverRefusesAndAnUnaskedRelativeIsNotOnOne() {
        AccompliceGate.Input zero = new AccompliceGate.Input(true, true, true, true, true, false, false,
                false, false, 100, 65, 9999L, 0L, 10_000L);
        assertTrue(AccompliceGate.decide(zero).available());
    }

    @Test
    void theConfiguredScopeDropsNamesNobodyCanParse() {
        Set<FamilyTier> scope = AccompliceGate.scope(List.of("SPOUSE", "cousin", " child ", ""));
        assertEquals(Set.of(FamilyTier.SPOUSE, FamilyTier.CHILD), scope);
    }
}
