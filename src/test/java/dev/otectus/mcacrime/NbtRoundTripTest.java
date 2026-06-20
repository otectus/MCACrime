package dev.otectus.mcacrime;

import dev.otectus.mcacrime.crime.Band;
import dev.otectus.mcacrime.state.DailyKarmaCounters;
import dev.otectus.mcacrime.state.PlayerCrimeData;
import dev.otectus.mcacrime.state.world.CrimeWorldData;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * NBT persistence fidelity — the backbone of the Phase-1 gate (Karma/Heat survive logout/death/restart,
 * spec §7.1, §18). Pure {@code CompoundTag} round-trips; no running game needed.
 */
class NbtRoundTripTest {

    @Test
    void playerCrimeDataRoundTrips() {
        PlayerCrimeData data = new PlayerCrimeData();
        UUID captive = UUID.randomUUID();
        UUID captor = UUID.randomUUID();
        data.setKarma(250L);
        data.setHeat(75L);
        data.setCachedBand(Band.BLUE);
        data.setWantedCached(true);
        data.setOnlineTicksLived(99_999L);
        data.setLastKarmaDecayTick(24_000L);
        data.setLastHeatDecayTick(1_200L);
        data.dailyKarmaCounters().rollTo(42L);
        data.dailyKarmaCounters().add("village:7", 5L);
        data.setHeldCaptiveRef(captive);
        data.setHeldByRef(captor);

        PlayerCrimeData loaded = new PlayerCrimeData();
        loaded.load(data.save());

        assertEquals(250L, loaded.getKarma());
        assertEquals(75L, loaded.getHeat());
        assertEquals(Band.BLUE, loaded.getCachedBand());
        assertTrue(loaded.isWantedCached());
        assertEquals(99_999L, loaded.getOnlineTicksLived());
        assertEquals(24_000L, loaded.getLastKarmaDecayTick());
        assertEquals(1_200L, loaded.getLastHeatDecayTick());
        assertEquals(42L, loaded.dailyKarmaCounters().dayEpoch());
        assertEquals(5L, loaded.dailyKarmaCounters().get("village:7"));
        assertEquals(captive, loaded.getHeldCaptiveRef());
        assertEquals(captor, loaded.getHeldByRef());
    }

    @Test
    void copyFromPreservesEverything() {
        PlayerCrimeData original = new PlayerCrimeData();
        original.setKarma(-150L);
        original.setHeat(60L);
        original.setCachedBand(Band.RED);
        original.setWantedCached(true);
        original.setOnlineTicksLived(7L);

        PlayerCrimeData fresh = new PlayerCrimeData();
        fresh.copyFrom(original);

        assertEquals(-150L, fresh.getKarma());
        assertEquals(60L, fresh.getHeat());
        assertEquals(Band.RED, fresh.getCachedBand());
        assertTrue(fresh.isWantedCached());
        assertEquals(7L, fresh.getOnlineTicksLived());
    }

    @Test
    void absentKeysLoadSaneDefaults() {
        PlayerCrimeData data = new PlayerCrimeData();
        data.load(new CompoundTag()); // simulate an old save / fresh attach
        assertEquals(0L, data.getKarma());
        assertEquals(0L, data.getHeat());
        assertEquals(Band.GREY, data.getCachedBand());
        assertNull(data.getHeldCaptiveRef());
        assertNull(data.getHeldByRef());
    }

    @Test
    void bandDerivedFromKarmaWhenBandKeyAbsent() {
        CompoundTag tag = new CompoundTag();
        tag.putLong("karma", 250L); // band key intentionally missing
        PlayerCrimeData data = new PlayerCrimeData();
        data.load(tag);
        assertEquals(Band.BLUE, data.getCachedBand());
    }

    @Test
    void dailyCountersResetOnNewDay() {
        DailyKarmaCounters counters = new DailyKarmaCounters();
        counters.rollTo(10L);
        counters.add("k", 8L);
        assertEquals(8L, counters.get("k"));
        counters.rollTo(11L); // new day
        assertEquals(0L, counters.get("k"));
        assertEquals(11L, counters.dayEpoch());
    }

    @Test
    void crimeWorldDataReputationRoundTrips() {
        CrimeWorldData data = new CrimeWorldData();
        UUID player = UUID.randomUUID();
        data.addReputation(7, player, 5);
        data.addReputation(7, player, 3); // merges to 8

        CrimeWorldData loaded = CrimeWorldData.load(data.save(new CompoundTag()));
        assertEquals(8, loaded.reputation(7, player));
        assertEquals(0, loaded.reputation(9, player)); // unknown village
    }

    @Test
    void crimeWorldDataPreservesReservedSlots() {
        // "ledger" is now a live structure (Phase 2); "bounties" remains a forward-compatible reserved slot.
        CompoundTag input = new CompoundTag();
        ListTag bounties = new ListTag();
        CompoundTag entry = new CompoundTag();
        entry.putString("reward", "100");
        bounties.add(entry);
        input.put("bounties", bounties); // a future-phase structure this version doesn't understand

        CrimeWorldData data = CrimeWorldData.load(input);
        CompoundTag out = data.save(new CompoundTag());
        assertTrue(out.contains("bounties"), "reserved 'bounties' slot must survive a load+save round-trip");
        assertEquals(1, out.getList("bounties", 10).size()); // 10 = TAG_COMPOUND
    }
}
