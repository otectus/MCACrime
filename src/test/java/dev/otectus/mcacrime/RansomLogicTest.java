package dev.otectus.mcacrime;

import dev.otectus.mcacrime.ransom.PayerResolver;
import dev.otectus.mcacrime.ransom.PayerResolver.Candidate;
import dev.otectus.mcacrime.ransom.PayerTier;
import dev.otectus.mcacrime.ransom.RansomCalculator;
import dev.otectus.mcacrime.ransom.RansomCooldowns;
import dev.otectus.mcacrime.ransom.RansomState;
import dev.otectus.mcacrime.ransom.RansomStatus;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pure ransom logic (spec §8.5, §18): payer priority, cooldown windows, amount math, and state NBT/transitions. */
class RansomLogicTest {

    private static Candidate c(PayerTier tier, boolean adult, boolean reachable) {
        return new Candidate(UUID.randomUUID(), tier, adult, reachable);
    }

    @Test
    void payerPriorityPrefersSpouseThenParent() {
        Candidate spouse = c(PayerTier.SPOUSE, true, true);
        Candidate parent = c(PayerTier.PARENT, true, true);
        assertEquals(spouse, PayerResolver.resolve(List.of(parent, spouse), true).orElseThrow());
        assertEquals(parent, PayerResolver.resolve(List.of(parent), true).orElseThrow());
    }

    @Test
    void unreachableCandidatesAreSkipped() {
        Candidate offlineSpouse = c(PayerTier.SPOUSE, true, false);
        Candidate onlineSibling = c(PayerTier.SIBLING, true, true);
        assertEquals(onlineSibling, PayerResolver.resolve(List.of(offlineSpouse, onlineSibling), true).orElseThrow());
    }

    @Test
    void nonAdultChildIsSkipped() {
        Candidate childMinor = c(PayerTier.ADULT_CHILD, false, true);
        // Only the minor child exists -> falls through to the village fallback.
        assertEquals(PayerTier.VILLAGE_AUTHORITY,
                PayerResolver.resolve(List.of(childMinor), true).orElseThrow().tier());
    }

    @Test
    void noFamilyDowngradesToVillageOrFails() {
        assertEquals(PayerTier.VILLAGE_AUTHORITY, PayerResolver.resolve(List.of(), true).orElseThrow().tier());
        assertTrue(PayerResolver.resolve(List.of(), false).isEmpty()); // fallback disabled -> no payer
    }

    @Test
    void cooldownWindowsAreIndependent() {
        // victim window still open -> not ready, even with family/village clear
        assertFalse(RansomCooldowns.ready(100L, 90L, 50L, 0L, 50L, 0L, 50L));
        // all windows elapsed -> ready
        assertTrue(RansomCooldowns.ready(200L, 90L, 50L, 90L, 50L, 90L, 50L));
        // zero window is always clear
        assertTrue(RansomCooldowns.ready(100L, 99L, 0L, 0L, 0L, 0L, 0L));
    }

    @Test
    void amountScalesByTierMultiplier() {
        assertEquals(32L, RansomCalculator.amount(16L, 2.0));
        assertEquals(12L, RansomCalculator.amount(16L, 0.75));
        assertEquals(0L, RansomCalculator.amount(16L, 0.0));
        assertEquals(0L, RansomCalculator.amount(-5L, 2.0)); // clamped non-negative
    }

    @Test
    void stateRoundTripsAndStatusIsTerminal() {
        RansomState s = new RansomState(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), PayerTier.SPOUSE, 40L, 100L, 1000L);
        RansomState loaded = RansomState.load(s.save());
        assertEquals(s.getVictim(), loaded.getVictim());
        assertEquals(s.getCaptor(), loaded.getCaptor());
        assertEquals(s.getPayer(), loaded.getPayer());
        assertEquals(PayerTier.SPOUSE, loaded.getTier());
        assertEquals(40L, loaded.getAmount());
        assertEquals(RansomStatus.OPEN, loaded.getStatus());
        assertFalse(RansomStatus.OPEN.isTerminal());
        assertTrue(RansomStatus.PAID.isTerminal());
    }

    @Test
    void villageFallbackStateLoadsWithNullPayer() {
        RansomState s = new RansomState(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                null, PayerTier.VILLAGE_AUTHORITY, 12L, 0L, 0L);
        RansomState loaded = RansomState.load(s.save());
        assertEquals(null, loaded.getPayer());
        assertEquals(PayerTier.VILLAGE_AUTHORITY, loaded.getTier());
    }
}
