package dev.otectus.mcacrime.compat;

import dev.otectus.mcacrime.McaCrimeConfig;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;

import javax.annotation.Nullable;
import java.lang.ref.WeakReference;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A few ticks of memory in front of the Townstead bridge.
 *
 * <p>The awareness predicates in {@code ai/NpcAwareness} are asked inside entity filters — the witness
 * scan, the hearing scan, the guard scan, the ally count in {@code ThreatContexts} — so a single crime
 * can ask "is this villager collapsed?" dozens of times about dozens of villagers within one tick.
 * Each of those is a reflective call chain through {@code compat/townstead}. Caching for
 * {@code townstead.snapshotCacheTicks} game ticks (default 20) turns that into one read per villager
 * per second, which is well inside the resolution of anything that consumes it: a collapse is not a
 * per-tick event.
 *
 * <h2>What it does not do</h2>
 *
 * <p>Nothing here is persisted, and nothing here is authoritative. A snapshot is derived state with a
 * deadline; the bridge stays the only source. {@link TownsteadQueryResult} results are not cached —
 * only the two views MCA: Crime actually gates behaviour on, and an unavailable read is cached as
 * <em>untracked</em> so a missing capability costs one reflective attempt per interval rather than one
 * per filter.
 *
 * <h2>Why the entity is weakly held</h2>
 *
 * <p>An entry is keyed by UUID and dimension, and holds the entity only through a
 * {@link WeakReference}, used solely to confirm the entry still describes <em>this</em> object. A
 * villager that unloads is collected, its entry stops matching and is dropped on the next sweep; a
 * villager that comes back is a new object and gets a fresh read. A cache that pinned entities would
 * be a memory leak with a game in it.
 */
public final class TownsteadSnapshotCache {

    /** Used when the config is unavailable, which is every unit test. */
    public static final int DEFAULT_CACHE_TICKS = 20;

    /**
     * One villager's Townstead state, as of {@code readAt}.
     *
     * @param needs     never null; {@link TownsteadNeedsView#untracked()} when there was no reading
     * @param lifeStage null when the stage could not be read at all, which reads as "capable"
     */
    public record Snapshot(TownsteadNeedsView needs, @Nullable TownsteadLifeStageView lifeStage, long readAt) {

        public Snapshot {
            needs = needs == null ? TownsteadNeedsView.untracked() : needs;
        }

        /** Whether Townstead has this villager off their feet. False whenever the reading is not real. */
        public boolean incapacitated() {
            return needs.incapacitated();
        }

        /** Whether the life stage can move. True when unknown: "we could not ask" is not "frozen". */
        public boolean mobile() {
            return lifeStage == null || !lifeStage.flagsKnown() || lifeStage.mobile();
        }

        /** Whether the life stage can be spoken to. True when unknown, for the same reason. */
        public boolean talkable() {
            return lifeStage == null || !lifeStage.flagsKnown() || lifeStage.talkable();
        }
    }

    /** The answer for a villager nothing is known about: tracked nothing, capable on every axis. */
    public static final Snapshot UNKNOWN = new Snapshot(TownsteadNeedsView.untracked(), null, Long.MIN_VALUE);

    private record Key(UUID entity, @Nullable ResourceLocation dimension) {
    }

    private record Entry(Snapshot snapshot, WeakReference<Entity> source) {
    }

    private static final Map<Key, Entry> CACHE = new ConcurrentHashMap<>();

    /** The server the cached entries belong to; a different one invalidates the lot. */
    private static volatile WeakReference<Object> owner = new WeakReference<>(null);

    private TownsteadSnapshotCache() {
    }

    /** How long an entry is reused. Falls back to {@link #DEFAULT_CACHE_TICKS} with no config loaded. */
    public static int cacheTicks() {
        try {
            return McaCrimeConfig.COMMON.townsteadSnapshotCacheTicks.get();
        } catch (Throwable t) {
            return DEFAULT_CACHE_TICKS;
        }
    }

    /**
     * This villager's Townstead state, read at most once per interval.
     *
     * <p>Never throws and never blocks: an absent Townstead, an unbound capability and a failed read
     * all produce {@link #UNKNOWN}, which every consumer treats as "behave exactly as MCA: Crime does
     * without Townstead".
     */
    public static Snapshot snapshot(@Nullable Entity entity) {
        if (entity == null || !TownsteadBridge.isAvailable()) {
            return UNKNOWN;
        }
        try {
            if (entity.level() == null || entity.level().isClientSide()) {
                return UNKNOWN;
            }
            Object server = entity.getServer();
            if (server == null) {
                return UNKNOWN;
            }
            if (owner.get() != server) {
                CACHE.clear();
                owner = new WeakReference<>(server);
            }
            long now = entity.level().getGameTime();
            Key key = new Key(entity.getUUID(), entity.level().dimension().location());
            Entry cached = CACHE.get(key);
            if (cached != null && cached.source().get() == entity
                    && now - cached.snapshot().readAt() < Math.max(1, cacheTicks())
                    && now >= cached.snapshot().readAt()) {
                return cached.snapshot();
            }
            Snapshot fresh = read(entity, now);
            CACHE.put(key, new Entry(fresh, new WeakReference<>(entity)));
            return fresh;
        } catch (Throwable t) {
            // A compat layer that can take an entity filter down is worse than one that reports nothing.
            return UNKNOWN;
        }
    }

    private static Snapshot read(Entity entity, long now) {
        TownsteadNeedsView needs = TownsteadBridge.needs(entity).orElse(TownsteadNeedsView.untracked());
        TownsteadLifeStageView stage = TownsteadBridge.lifeStage(entity).orElse(null);
        return new Snapshot(needs, stage, now);
    }

    /** Drops entries whose villager has been collected, and anything older than the interval. */
    public static void sweep(long now) {
        if (CACHE.isEmpty()) {
            return;
        }
        int ttl = Math.max(1, cacheTicks());
        CACHE.values().removeIf(entry -> entry.source().get() == null
                || now - entry.snapshot().readAt() >= ttl);
    }

    /** Drops the lot. Called on server stop, and whenever the bridge is released. */
    public static void clearAll() {
        CACHE.clear();
        owner = new WeakReference<>(null);
    }

    /** Exposed for {@code /crime debug townstead}: how many villagers are currently cached. */
    public static int size() {
        return CACHE.size();
    }
}
