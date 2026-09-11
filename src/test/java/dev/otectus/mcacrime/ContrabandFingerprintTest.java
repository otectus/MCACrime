package dev.otectus.mcacrime;

import dev.otectus.mcacrime.enforcement.ContrabandFingerprint;
import dev.otectus.mcacrime.item.contraband.ContrabandProbe;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The dedupe key for a contraband find, and the decision to charge for it. */
class ContrabandFingerprintTest {

    private static ContrabandProbe probe(String id, int count) {
        return new ContrabandProbe(ResourceLocation.parse(id), tag -> false, count, 0, id,
                ContrabandProbe.ContrabandSlot.MAIN);
    }

    @Test
    void orderDoesNotChangeTheFingerprint() {
        long a = ContrabandFingerprint.of(List.of(probe("minecraft:tnt", 3), probe("minecraft:gunpowder", 1)));
        long b = ContrabandFingerprint.of(List.of(probe("minecraft:gunpowder", 1), probe("minecraft:tnt", 3)));
        assertEquals(a, b);
    }

    @Test
    void countChangesTheFingerprint() {
        assertNotEquals(ContrabandFingerprint.of(List.of(probe("minecraft:tnt", 3))),
                ContrabandFingerprint.of(List.of(probe("minecraft:tnt", 4))));
    }

    @Test
    void emptyHaulIsZero() {
        assertEquals(0L, ContrabandFingerprint.of(List.of()));
        assertEquals(0L, ContrabandFingerprint.of(null));
    }

    @Test
    void chargeMatrix() {
        long fingerprint = ContrabandFingerprint.of(List.of(probe("minecraft:tnt", 1)));
        // Nothing found: never a charge, whatever the clock says.
        assertFalse(ContrabandFingerprint.shouldCharge(0L, 0L, 10_000L, 0L, 24_000L));
        // A different haul is a new crime immediately.
        assertTrue(ContrabandFingerprint.shouldCharge(fingerprint, 99L, 100L, 90L, 24_000L));
        // The same haul inside the window is the same crime, already charged.
        assertFalse(ContrabandFingerprint.shouldCharge(fingerprint, fingerprint, 1_000L, 500L, 24_000L));
        // The same haul after the window is chargeable again.
        assertTrue(ContrabandFingerprint.shouldCharge(fingerprint, fingerprint, 25_000L, 500L, 24_000L));
        // A zero window means every search charges.
        assertTrue(ContrabandFingerprint.shouldCharge(fingerprint, fingerprint, 500L, 500L, 0L));
    }
}
