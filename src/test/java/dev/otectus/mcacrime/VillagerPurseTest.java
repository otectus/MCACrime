package dev.otectus.mcacrime;

import dev.otectus.mcacrime.economy.account.VillagerPurse;
import dev.otectus.mcacrime.memory.OffenderMemory;
import dev.otectus.mcacrime.memory.VillagerCrimeProfile;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class VillagerPurseTest {
    @Test
    void withdrawNeverCreatesValueOrGoesNegative() {
        VillagerPurse purse = new VillagerPurse(3, 5, 1, 0);
        assertEquals(3, purse.withdraw(100));
        assertEquals(0, purse.balance());
        assertEquals(0, purse.withdraw(1));
    }

    @Test
    void lazyRefillHasNoMultiDayCatchUpWindfall() {
        VillagerPurse purse = new VillagerPurse(0, 5, 1, 0);
        purse.refill(100);
        assertEquals(1, purse.balance());
        purse.refill(100);
        assertEquals(1, purse.balance());
        purse.refill(101);
        assertEquals(2, purse.balance());
    }

    @Test
    void profileRoundTripKeepsPurseAndDirectVictimMemory() {
        UUID villager = UUID.randomUUID();
        UUID offender = UUID.randomUUID();
        VillagerCrimeProfile profile = new VillagerCrimeProfile(villager, new VillagerPurse(4, 5, 1, 8));
        profile.memory(offender).recordAttempt(200, 600, 40);
        profile.memory(offender).recordSuccess(260, 3);
        VillagerCrimeProfile loaded = VillagerCrimeProfile.load(profile.save());
        assertNotNull(loaded);
        assertEquals(4, loaded.purse().balance());
        OffenderMemory memory = loaded.offenderMemories().get(offender);
        assertNotNull(memory);
        assertTrue(memory.pendingReport());
        assertEquals(3, memory.stolenValue());
        assertEquals(800, memory.fearUntil());
        assertEquals(240, memory.panicUntil());
    }

    @Test
    void legacyZeroInitialPurseIsRepairedExactlyOnce() {
        CompoundTag legacy = new CompoundTag();
        legacy.putInt("balance", 0);
        legacy.putInt("capacity", 5);
        legacy.putInt("dailyIncome", 1);
        VillagerPurse purse = VillagerPurse.load(legacy);

        assertTrue(purse.ensureNonZeroInitialSeed(1));
        assertEquals(1, purse.balance());
        assertFalse(purse.ensureNonZeroInitialSeed(4));
        assertEquals(1, purse.balance(), "a legitimately depleted upgraded purse must not be reseeded");
    }
}
