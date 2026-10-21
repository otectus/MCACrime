package dev.otectus.mcacrime;

import dev.otectus.mcacrime.mug.npc.StolenGoodsLedger;
import dev.otectus.mcacrime.state.world.CrimeWorldData;
import dev.otectus.mcacrime.state.world.CrimeDataMigrations;
import dev.otectus.mcacrime.state.world.StolenGoodsRecord;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** A saved description of player inventory is never a second, claimable copy of that inventory. */
class StolenGoodsCustodyTest {
    private final UUID player = UUID.randomUUID();
    private final UUID owner = UUID.randomUUID();

    private StolenGoodsRecord diamond(UUID thief, StolenGoodsRecord.Custody custody) {
        CompoundTag stack = new CompoundTag();
        stack.putString("id", "minecraft:diamond");
        stack.putByte("Count", (byte) 1);
        return new StolenGoodsRecord(UUID.randomUUID(), thief, owner, stack, 0L, 0L, "", custody);
    }

    @Test
    void sevenDayExpiryRetiresPlayerProvenanceWithoutMintingEscrowAfterReload() {
        CrimeWorldData data = new CrimeWorldData();
        var record = diamond(player, StolenGoodsRecord.Custody.INVENTORY);
        data.putStolenGoods(record);
        data = CrimeWorldData.load(data.save(new CompoundTag()));

        assertEquals(record, data.stolenGoods(record.transactionId()));
        assertEquals(1, StolenGoodsLedger.expire(data, 7, 7, Set.of()));
        assertTrue(data.stolenGoods().isEmpty());
        assertTrue(data.propertyEscrowFor(owner).isEmpty(), "the player still owns the physical diamond");
        assertEquals(0, StolenGoodsLedger.expire(data, 8, 7, Set.of()));
    }

    @Test
    void neitherClaimNorDeathNorArrestCanRecoverInventoryProvenance() {
        CrimeWorldData data = new CrimeWorldData();
        var record = diamond(player, StolenGoodsRecord.Custody.INVENTORY);
        data.putStolenGoods(record);
        assertTrue(StolenGoodsLedger.claimAll(data, player, ignored -> {
            fail("inventory provenance reached a delivery callback");
            return true;
        }, 1L).isEmpty());
        assertTrue(StolenGoodsLedger.claimForOwner(data, player, owner).isEmpty());
        assertEquals(0, StolenGoodsLedger.escrowAll(data, player, 1L));
        assertEquals(0, StolenGoodsLedger.escrowForOwner(data, player, owner, 1L));
        assertTrue(data.propertyEscrowFor(owner).isEmpty());
        assertEquals(record, data.stolenGoods(record.transactionId()), "keep the investigation trail until expiry");
    }

    @Test
    void expiryStillRecoversTheNpcCopyExactlyOnce() {
        CrimeWorldData data = new CrimeWorldData();
        var held = diamond(UUID.randomUUID(), StolenGoodsRecord.Custody.LEDGER);
        data.putStolenGoods(held);
        data.putStolenGoods(diamond(player, StolenGoodsRecord.Custody.INVENTORY));
        assertEquals(2, StolenGoodsLedger.expire(data, 7, 7, Set.of()));
        assertEquals(1, data.propertyEscrowFor(owner).size());
        assertEquals(held.stackTag(), data.propertyEscrowFor(owner).get(0).stackTag());
        assertEquals(held.transactionId(), data.propertyEscrowFor(owner).get(0).sourceRecordId());
        assertEquals(0, StolenGoodsLedger.escrowAll(data, held.thief(), 8L));
    }

    @Test
    void retentionAndActiveTheftRulesAlsoProtectInventoryProvenance() {
        CrimeWorldData data = new CrimeWorldData();
        data.putStolenGoods(diamond(player, StolenGoodsRecord.Custody.INVENTORY));
        assertEquals(0, StolenGoodsLedger.expire(data, 6, 7, Set.of()));
        assertEquals(0, StolenGoodsLedger.expire(data, 8, 0, Set.of()));
        assertEquals(0, StolenGoodsLedger.expire(data, 8, 7, Set.of(player)));
        assertEquals(1, data.stolenGoods().size());
    }

    private StolenGoodsRecord legacy(UUID thief) {
        CompoundTag old = diamond(thief, StolenGoodsRecord.Custody.LEDGER).save();
        old.remove("custody");
        return StolenGoodsRecord.load(old);
    }

    @Test
    void legacyRecordsWaitForIdentityResolutionInsteadOfGuessingFromAnEmptyProvider() {
        CrimeWorldData data = new CrimeWorldData();
        var record = legacy(player);
        data.putStolenGoods(record);
        assertEquals(StolenGoodsRecord.Custody.LEGACY, record.custody());
        assertEquals(0, StolenGoodsLedger.expire(data, 7, 7, Set.of()));
        assertTrue(StolenGoodsLedger.claimAll(data, player).isEmpty());
        assertEquals(0, StolenGoodsLedger.escrowAll(data, player, 7L));
        assertEquals(record, data.stolenGoods(record.transactionId()));
    }

    @Test
    void legacyPlayerAndNpcRecordsMigrateDifferentlyAndSurviveAnotherSave() {
        CrimeWorldData data = new CrimeWorldData();
        var physical = legacy(player);
        var npc = legacy(UUID.randomUUID());
        data.putStolenGoods(physical);
        data.putStolenGoods(npc);
        StolenGoodsLedger.reconcileLegacyCustody(data, Set.of(player));
        data = CrimeWorldData.load(data.save(new CompoundTag()));
        assertEquals(StolenGoodsRecord.Custody.INVENTORY, data.stolenGoods(physical.transactionId()).custody());
        assertEquals(StolenGoodsRecord.Custody.LEDGER, data.stolenGoods(npc.transactionId()).custody());
        // Repeating migration cannot reinterpret explicit custody.
        StolenGoodsLedger.reconcileLegacyCustody(data, Set.of(npc.thief()));
        assertEquals(2, StolenGoodsLedger.expire(data, 7, 7, Set.of()));
        assertEquals(1, data.propertyEscrowFor(owner).size());
        assertEquals(npc.transactionId(), data.propertyEscrowFor(owner).get(0).sourceRecordId());
    }

    @Test
    void schemaUpgradePreservesUnclassifiedPropertyAndGatesOlderReaders() {
        CrimeWorldData data = new CrimeWorldData();
        var record = legacy(player);
        data.putStolenGoods(record);
        CompoundTag old = data.save(new CompoundTag());
        old.putInt(CrimeDataMigrations.TAG_SCHEMA, CrimeDataMigrations.SCHEMA_DECREES);
        old.getList("stolenGoods", Tag.TAG_COMPOUND).getCompound(0).remove("custody");
        CompoundTag migrated = CrimeDataMigrations.migrate(old);
        assertEquals(18, migrated.getInt(CrimeDataMigrations.TAG_SCHEMA));
        assertEquals(CrimeDataMigrations.SCHEMA_DECREES + 1, CrimeDataMigrations.SCHEMA_STOLEN_CUSTODY);
        assertEquals(CrimeDataMigrations.SCHEMA_STOLEN_CUSTODY, CrimeDataMigrations.CURRENT_SCHEMA);
        assertEquals(old.getList("stolenGoods", Tag.TAG_COMPOUND), migrated.getList("stolenGoods", Tag.TAG_COMPOUND));
        assertEquals(17, old.getInt(CrimeDataMigrations.TAG_SCHEMA), "migration changed the input");
        CrimeWorldData loaded = CrimeWorldData.load(migrated);
        assertEquals(StolenGoodsRecord.Custody.LEGACY, loaded.stolenGoods(record.transactionId()).custody());
        assertEquals(0, StolenGoodsLedger.expire(loaded, 7, 7, Set.of()));
    }
}
