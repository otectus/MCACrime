package dev.otectus.mcacrime.state;

import net.minecraft.nbt.CompoundTag;

import java.util.HashMap;
import java.util.Map;

/**
 * Per-player daily anti-farm tallies (spec §3.3): how much positive Karma a player has gained today
 * from a given source (per-villager, per-village, per-player-global), so passive trade/gift grinding
 * can be capped. The counters reset when the MC-day epoch ({@code floor(gameTime / 24000)}) changes, so
 * "per day" is measured in game days a player has actually been around for.
 *
 * <p>Persisted + epoch-reset logic is live in 0.1.0, but no positive-Karma source writes here yet
 * (trade/quest/defense hooks are later phases), so the map stays empty for now. Pure (NBT only) so it
 * round-trips in unit tests.
 */
public final class DailyKarmaCounters {

    private final Map<String, Long> counters = new HashMap<>();
    private long dayEpoch;

    /**
     * Resets the tallies if the day changed. Call before reading/adding so a stale day's totals never
     * leak into a new day.
     */
    public void rollTo(long epoch) {
        if (epoch != dayEpoch) {
            dayEpoch = epoch;
            counters.clear();
        }
    }

    public long get(String key) {
        return counters.getOrDefault(key, 0L);
    }

    /** Adds {@code amount} to {@code key} for the current day and returns the new running total. */
    public long add(String key, long amount) {
        long updated = get(key) + amount;
        counters.put(key, updated);
        return updated;
    }

    public long dayEpoch() {
        return dayEpoch;
    }

    public void copyFrom(DailyKarmaCounters other) {
        counters.clear();
        counters.putAll(other.counters);
        dayEpoch = other.dayEpoch;
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putLong("dayEpoch", dayEpoch);
        CompoundTag values = new CompoundTag();
        counters.forEach(values::putLong);
        tag.put("values", values);
        return tag;
    }

    public void load(CompoundTag tag) {
        counters.clear();
        dayEpoch = tag.getLong("dayEpoch");
        CompoundTag values = tag.getCompound("values");
        for (String key : values.getAllKeys()) {
            counters.put(key, values.getLong(key));
        }
    }
}
