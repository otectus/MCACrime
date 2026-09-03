package dev.otectus.mcacrime;

import dev.otectus.mcacrime.state.world.CrimeWorldData;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class ActionEconomyWorldDataTest {
    @Test
    void treasuryCannotOverdrawAndReceiptCommitsOnceAcrossReload() {
        CrimeWorldData data = new CrimeWorldData();
        assertEquals(10, data.treasuryBalance("overworld:1", 10));
        assertFalse(data.withdrawTreasury("overworld:1", 11, 10));
        assertTrue(data.withdrawTreasury("overworld:1", 7, 10));
        assertEquals(3, data.treasuryBalance("overworld:1", 999));

        UUID transaction = UUID.randomUUID();
        assertTrue(data.recordTransactionReceipt(transaction));
        assertFalse(data.recordTransactionReceipt(transaction));
        CrimeWorldData loaded = CrimeWorldData.load(data.save(new CompoundTag()));
        assertEquals(3, loaded.treasuryBalance("overworld:1", 999));
        assertTrue(loaded.hasTransactionReceipt(transaction));
        assertFalse(loaded.recordTransactionReceipt(transaction));
    }

    @Test
    void schemaFourInitializesActionEconomySections() {
        // Asserted on the single step rather than on a full migrate(), so adding a later schema does
        // not make this test about the newest one.
        CompoundTag schemaThree = new CompoundTag();
        schemaThree.putInt("schema", 3);
        CompoundTag migrated = dev.otectus.mcacrime.state.world.CrimeDataMigrations.v3to4(schemaThree);
        assertEquals(4, migrated.getInt("schema"));
        assertTrue(migrated.contains("villagerProfiles"));
        assertTrue(migrated.contains("transactionReceipts"));
    }
}
