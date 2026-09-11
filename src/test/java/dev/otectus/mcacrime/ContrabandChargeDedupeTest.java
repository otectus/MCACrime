package dev.otectus.mcacrime;

import dev.otectus.mcacrime.enforcement.ContrabandFingerprint;
import dev.otectus.mcacrime.item.contraband.ContrabandProbe;
import dev.otectus.mcacrime.item.contraband.ContrabandProbe.ContrabandSlot;
import dev.otectus.mcacrime.state.PlayerCrimeData;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The same haul is charged once (0.7.0, plan §5.3).
 *
 * <p>The seam is the pair the search service uses: {@link ContrabandFingerprint} decides, and the two
 * {@link PlayerCrimeData} fields remember. Without this a player carrying one banned item past a
 * village of guards would be charged once per guard per pass, which is the way a possession offence
 * turns into a Heat pump.
 */
class ContrabandChargeDedupeTest {

    private static final long RECHARGE = 24_000L;

    private static ContrabandProbe probe(String id, int count) {
        return new ContrabandProbe(ResourceLocation.parse(id), tag -> false, count, 0, id, ContrabandSlot.MAIN);
    }

    /** The search service's own sequence: decide, then store on a charge. */
    private static boolean charge(PlayerCrimeData data, List<ContrabandProbe> listed, long now) {
        long fingerprint = ContrabandFingerprint.of(listed);
        boolean chargeable = ContrabandFingerprint.shouldCharge(fingerprint,
                data.getLastContrabandFingerprint(), now, data.getLastContrabandChargeTick(), RECHARGE);
        if (chargeable) {
            data.setLastContrabandFingerprint(fingerprint);
            data.setLastContrabandChargeTick(now);
        }
        return chargeable;
    }

    @Test
    void theSameHaulFoundAgainInsideTheWindowIsNotChargedTwice() {
        PlayerCrimeData data = new PlayerCrimeData();
        List<ContrabandProbe> haul = List.of(probe("minecraft:tnt", 4));
        assertTrue(charge(data, haul, 100L));
        assertFalse(charge(data, haul, 140L), "the next guard on the same patrol finds the same crime");
        assertFalse(charge(data, haul, 100L + RECHARGE - 1L));
        assertEquals(100L, data.getLastContrabandChargeTick(), "a refused charge does not move the clock");
    }

    @Test
    void aChangedHaulIsChargedImmediately() {
        PlayerCrimeData data = new PlayerCrimeData();
        assertTrue(charge(data, List.of(probe("minecraft:tnt", 4)), 100L));
        assertTrue(charge(data, List.of(probe("minecraft:tnt", 5)), 120L), "a bigger stack is a new haul");
        assertTrue(charge(data, List.of(probe("minecraft:tnt", 5), probe("minecraft:bell", 1)), 130L));
    }

    @Test
    void theSameHaulIsChargeableAgainOnceTheRechargeWindowHasPassed() {
        PlayerCrimeData data = new PlayerCrimeData();
        List<ContrabandProbe> haul = List.of(probe("minecraft:tnt", 4));
        assertTrue(charge(data, haul, 100L));
        assertTrue(charge(data, haul, 100L + RECHARGE));
        assertEquals(100L + RECHARGE, data.getLastContrabandChargeTick());
    }

    @Test
    void shufflingAnInventoryIsNotASecondCrime() {
        List<ContrabandProbe> one = List.of(probe("minecraft:tnt", 4), probe("minecraft:bell", 1));
        List<ContrabandProbe> other = List.of(probe("minecraft:bell", 1), probe("minecraft:tnt", 4));
        assertEquals(ContrabandFingerprint.of(one), ContrabandFingerprint.of(other));
        PlayerCrimeData data = new PlayerCrimeData();
        assertTrue(charge(data, one, 100L));
        assertFalse(charge(data, other, 200L));
    }

    @Test
    void anEmptySearchIsNeverAChargeAndNeverOverwritesTheLastOne() {
        PlayerCrimeData data = new PlayerCrimeData();
        assertTrue(charge(data, List.of(probe("minecraft:tnt", 4)), 100L));
        long remembered = data.getLastContrabandFingerprint();
        assertNotEquals(0L, remembered);
        assertFalse(charge(data, List.of(), 200L));
        assertEquals(remembered, data.getLastContrabandFingerprint());
    }
}
