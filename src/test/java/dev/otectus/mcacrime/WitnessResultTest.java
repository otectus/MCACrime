package dev.otectus.mcacrime;

import dev.otectus.mcacrime.detect.WitnessResult;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The loyal-witness component added to {@link WitnessResult}: every factory that existed before it
 * still says "nobody kept quiet", which is what made adding the component a change no caller had to
 * notice.
 */
class WitnessResultTest {

    private static UUID uuid(int n) {
        return UUID.fromString(String.format("00000000-0000-0000-0000-%012d", n));
    }

    @Test
    void everyLegacyFactoryYieldsNoLoyalWitnesses() {
        assertTrue(WitnessResult.none().loyalIds().isEmpty());
        assertTrue(WitnessResult.official().loyalIds().isEmpty());
        assertTrue(WitnessResult.legacy(true, 3).loyalIds().isEmpty());
        assertTrue(WitnessResult.of(Set.of(uuid(1)), 1, 1).loyalIds().isEmpty());
        assertTrue(new WitnessResult(Set.of(uuid(1)), true, 1, 1).loyalIds().isEmpty());
    }

    @Test
    void theLoyaltyFactoryIsWitnessedOnlyWhenSomebodyWouldReport() {
        WitnessResult quiet = WitnessResult.of(Set.of(), Set.of(uuid(1)), 1, 0);
        WitnessResult mixed = WitnessResult.of(Set.of(uuid(2)), Set.of(uuid(1)), 2, 1);

        assertFalse(quiet.witnessed());
        assertEquals(Set.of(uuid(1)), quiet.loyalIds());
        assertTrue(mixed.witnessed());
        assertEquals(1, mixed.totalWitnesses());
    }

    @Test
    void aNullLoyalSetIsEmptyRatherThanAFailure() {
        WitnessResult result = new WitnessResult(Set.of(uuid(1)), true, 1, 1, null);

        assertTrue(result.loyalIds().isEmpty());
    }
}
