package dev.otectus.mcacrime;

import dev.otectus.mcacrime.mug.npc.StolenGoodsLedger;
import dev.otectus.mcacrime.state.world.CrimeWorldData;
import dev.otectus.mcacrime.state.world.StolenGoodsRecord;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Claiming stolen property, which is the one operation in this mod that must happen exactly once.
 *
 * <p>Everything below is one property restated: a claim removes what it returns. A second death
 * event, a reload between the drop and the save, or an arrest racing a kill must all find an empty
 * ledger rather than a second copy of the sword.
 *
 * <p>Run against {@link CrimeWorldData} directly, since world data can be built without a server and
 * a server cannot be built in a unit test. Records carry currency rather than items for the same
 * reason {@code CrimeDataMigrationTest} does: an {@code ItemStack} needs an item registry, and none
 * of the claim logic looks at one.
 */
class StolenGoodsLedgerTest {

    private static final UUID THIEF = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final UUID OTHER_THIEF = UUID.fromString("00000000-0000-0000-0000-0000000000a2");
    private static final UUID OWNER = UUID.fromString("00000000-0000-0000-0000-0000000000b1");
    private static final UUID OTHER_OWNER = UUID.fromString("00000000-0000-0000-0000-0000000000b2");

    private static UUID file(CrimeWorldData data, UUID thief, UUID owner, long currency, long stolenAt) {
        UUID transaction = UUID.randomUUID();
        data.putStolenGoods(new StolenGoodsRecord(transaction, thief, owner, null, currency, stolenAt));
        return transaction;
    }

    @Test
    void claimingTwiceReturnsTheGoodsOnce() {
        CrimeWorldData data = new CrimeWorldData();
        file(data, THIEF, OWNER, 5L, 0L);
        file(data, THIEF, OWNER, 7L, 0L);

        List<StolenGoodsRecord> first = StolenGoodsLedger.claimAll(data, THIEF);
        assertEquals(2, first.size());
        assertEquals(12L, first.stream().mapToLong(StolenGoodsRecord::currency).sum());

        assertTrue(StolenGoodsLedger.claimAll(data, THIEF).isEmpty(), "the second claim minted a duplicate");
        assertTrue(data.stolenGoodsByThief(THIEF).isEmpty());
    }

    @Test
    void aClaimTakesOnlyThisThiefsHoldings() {
        CrimeWorldData data = new CrimeWorldData();
        file(data, THIEF, OWNER, 5L, 0L);
        UUID untouched = file(data, OTHER_THIEF, OWNER, 9L, 0L);

        assertEquals(1, StolenGoodsLedger.claimAll(data, THIEF).size());
        assertEquals(1, data.stolenGoodsByThief(OTHER_THIEF).size());
        assertEquals(9L, data.stolenGoods(untouched).currency());
    }

    @Test
    void claimingForOneOwnerLeavesTheRestWithTheThief() {
        CrimeWorldData data = new CrimeWorldData();
        file(data, THIEF, OWNER, 5L, 0L);
        file(data, THIEF, OTHER_OWNER, 9L, 0L);

        List<StolenGoodsRecord> returned = StolenGoodsLedger.claimForOwner(data, THIEF, OWNER);
        assertEquals(1, returned.size());
        assertEquals(OWNER, returned.get(0).owner());

        List<StolenGoodsRecord> left = data.stolenGoodsByThief(THIEF);
        assertEquals(1, left.size());
        assertEquals(OTHER_OWNER, left.get(0).owner());
    }

    @Test
    void expiryLaundersOldGoodsAndLeavesRecentOnes() {
        CrimeWorldData data = new CrimeWorldData();
        file(data, THIEF, OWNER, 5L, 0L);                 // day 0
        UUID recent = file(data, THIEF, OWNER, 6L, 24000L * 9L);  // day 9

        assertEquals(1, StolenGoodsLedger.expire(data, 10L, 7, Set.of()));
        List<StolenGoodsRecord> left = data.stolenGoodsByThief(THIEF);
        assertEquals(1, left.size());
        assertEquals(recent, left.get(0).transactionId());
    }

    @Test
    void expiryNeverTouchesAThiefWhoIsStillWorking() {
        CrimeWorldData data = new CrimeWorldData();
        file(data, THIEF, OWNER, 5L, 0L);

        assertEquals(0, StolenGoodsLedger.expire(data, 10L, 7, Set.of(THIEF)));
        assertEquals(1, data.stolenGoodsByThief(THIEF).size());
    }

    @Test
    void zeroPersistenceDaysDisablesExpiryEntirely() {
        CrimeWorldData data = new CrimeWorldData();
        file(data, THIEF, OWNER, 5L, 0L);

        assertEquals(0, StolenGoodsLedger.expire(data, 10_000L, 0, Set.of()));
        assertEquals(1, data.stolenGoodsByThief(THIEF).size());
    }

    @Test
    void theByThiefIndexIsRebuiltOnLoadAndStillClaimable() {
        CrimeWorldData data = new CrimeWorldData();
        file(data, THIEF, OWNER, 5L, 0L);
        file(data, THIEF, OTHER_OWNER, 7L, 0L);
        file(data, OTHER_THIEF, OWNER, 9L, 0L);

        CrimeWorldData loaded = CrimeWorldData.load(data.save(new CompoundTag()));

        assertEquals(2, loaded.stolenGoodsByThief(THIEF).size(), "the index did not survive the save");
        assertEquals(2, StolenGoodsLedger.claimAll(loaded, THIEF).size());
        assertTrue(loaded.stolenGoodsByThief(THIEF).isEmpty());
        assertEquals(1, loaded.stolenGoodsByThief(OTHER_THIEF).size());
    }
}
