package dev.otectus.mcacrime;

import dev.otectus.mcacrime.state.world.CapacityResult;
import dev.otectus.mcacrime.state.world.CrimeDataMigrations;
import dev.otectus.mcacrime.state.world.CrimeWorldData;
import dev.otectus.mcacrime.state.world.StolenGoodsRecord;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * What happens at the ceiling (T22).
 *
 * <p>Every collection in world data has a cap, and the cap used to be enforced in the load loop: read
 * entries until the limit, then stop. That is the one place it must not be. The store had already been
 * paid for by the time it was written — the item was taken, the fine was charged, the cell was built —
 * so stopping at the cap did not prevent anything, it silently deleted the tail of somebody's save
 * every single time the world was opened, and deleted a bit more of it each time it was written back.
 *
 * <p>The rule now: read everything, refuse the next insertion. These tests walk the boundary at
 * cap-1, cap and cap+1 and assert nothing is lost at any of them, then check that an insertion into a
 * full table says so rather than pretending.
 *
 * <p>{@code stolenGoods} stands in for all eight capped tables — it is the cheapest one to fill and
 * the code path is shared. Currency-only records, so no item registry is needed.
 */
class LoadCapBoundaryTest {

    /** {@code CrimeWorldData.MAX_STOLEN_GOODS}, which is private and has no business being public. */
    private static final int CAP = 4096;

    private static final UUID THIEF = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final UUID OWNER = UUID.fromString("00000000-0000-0000-0000-0000000000b1");

    /** A store tag holding {@code count} stolen-goods rows, written by hand so the cap can be exceeded. */
    private static CompoundTag storeOf(int count) {
        ListTag rows = new ListTag();
        for (int i = 0; i < count; i++) {
            rows.add(new StolenGoodsRecord(idOf(i), THIEF, OWNER, null, i + 1L, 0L).save());
        }
        CompoundTag tag = new CompoundTag();
        tag.putInt(CrimeDataMigrations.TAG_SCHEMA, CrimeDataMigrations.CURRENT_SCHEMA);
        tag.put("stolenGoods", rows);
        return tag;
    }

    /** Deterministic ids, so a failure names the same row every run. */
    private static UUID idOf(int index) {
        return new UUID(0xC0FFEEL, index);
    }

    @Test
    void oneUnderTheCapRoundTrips() {
        assertRoundTrips(CAP - 1);
    }

    @Test
    void exactlyTheCapRoundTrips() {
        assertRoundTrips(CAP);
    }

    @Test
    void oneOverTheCapKeepsEveryRow() {
        assertRoundTrips(CAP + 1);
    }

    private static void assertRoundTrips(int count) {
        CrimeWorldData loaded = CrimeWorldData.load(storeOf(count));
        assertEquals(count, loaded.stolenGoods().size(),
                "the load loop dropped rows at the ceiling instead of reading them");

        CrimeWorldData again = CrimeWorldData.load(loaded.save(new CompoundTag()));
        assertEquals(count, again.stolenGoods().size(), "a save/load cycle lost rows over the ceiling");
        assertNotNull(again.stolenGoods(idOf(count - 1)), "the last row is the one a cap would eat first");
    }

    // ------------------------------------------------------------------ insertion

    @Test
    void anInsertionIntoAFullTableIsRefusedRatherThanEvicting() {
        CrimeWorldData data = CrimeWorldData.load(storeOf(CAP));

        CapacityResult result = data.putStolenGoods(
                new StolenGoodsRecord(UUID.randomUUID(), THIEF, OWNER, null, 1L, 0L));

        assertEquals(CapacityResult.FULL, result,
                "the caller has to be told before it takes the item it was about to record");
        assertEquals(CAP, data.stolenGoods().size(), "a full table evicted an existing row to make space");
    }

    @Test
    void aFullTableStillAcceptsUpdatesToRowsAlreadyInIt() {
        CrimeWorldData data = CrimeWorldData.load(storeOf(CAP));

        CapacityResult result = data.putStolenGoods(
                new StolenGoodsRecord(idOf(0), THIEF, OWNER, null, 999L, 0L));

        assertEquals(CapacityResult.OK, result,
                "a full table must stop new rows arriving, not freeze the ones already there");
        assertEquals(999L, data.stolenGoods(idOf(0)).currency());
    }

    @Test
    void anInsertionBelowTheCapIsAccepted() {
        CrimeWorldData data = CrimeWorldData.load(storeOf(CAP - 1));

        assertEquals(CapacityResult.OK, data.putStolenGoods(
                new StolenGoodsRecord(UUID.randomUUID(), THIEF, OWNER, null, 1L, 0L)));
        assertEquals(CAP, data.stolenGoods().size());
    }
}
