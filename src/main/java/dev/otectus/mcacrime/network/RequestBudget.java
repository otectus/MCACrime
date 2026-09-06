package dev.otectus.mcacrime.network;

import dev.otectus.mcacrime.McaCrime;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * A per-player token bucket in front of every packet the client is allowed to send.
 *
 * <p>Every C2S packet here does real server work — building a menu walks the handler registry, the
 * dossier rebuilds a bounded list from the ledger — and none of it is gated by anything the client
 * cannot spam. The bucket is the gate: a held keybind gets the rate a human would produce, and a
 * script gets the same rate.
 *
 * <p>Refused requests are counted and logged at most once per player per minute. Logging per packet
 * would hand a spammer the ability to fill the server log, which is the same denial of service by a
 * different route.
 */
public final class RequestBudget {

    /** Ticks between warnings about one player, however many requests they refused in between. */
    private static final long WARN_INTERVAL_TICKS = 1200L;

    /**
     * What a category is allowed. Rates are per tick because the clock is a tick clock; the comment
     * on each is the human-readable form the plan states.
     */
    public enum Category {
        /** Opening a menu: 4/s. */
        MENU(0.2D, 4),
        /** Starting an action: 8/s, because a menu click and its retry are both legitimate. */
        ACTION(0.4D, 8),
        /** Rebuilding the dossier: one per ten ticks, and no burst — it is a screen, not an input. */
        DOSSIER(0.1D, 1),
        /** Answering a guard: 8/s. */
        CHALLENGE(0.4D, 8);

        private final double tokensPerTick;
        private final int burst;

        Category(double tokensPerTick, int burst) {
            this.tokensPerTick = tokensPerTick;
            this.burst = burst;
        }

        public double tokensPerTick() {
            return tokensPerTick;
        }

        public int burst() {
            return burst;
        }
    }

    private record Key(UUID player, Category category) {
    }

    private static final Map<Key, Bucket> BUCKETS = new ConcurrentHashMap<>();

    /** Default clock: real time in ticks. A packet handler runs off-thread and has no level to ask. */
    private static final LongSupplier WALL_CLOCK = () -> System.currentTimeMillis() / 50L;

    private static volatile LongSupplier clock = WALL_CLOCK;

    private RequestBudget() {
    }

    /** Substitutes the tick clock. Tests only; production never calls this. */
    public static void setClock(LongSupplier ticks) {
        clock = ticks == null ? WALL_CLOCK : ticks;
    }

    /** Restores the real clock and drops every bucket. Tests only. */
    public static void reset() {
        clock = WALL_CLOCK;
        BUCKETS.clear();
    }

    /**
     * Whether this player may spend one request in this category right now.
     *
     * <p>A refusal consumes nothing: the bucket is only debited when the request is actually allowed
     * through, so a player who is being refused recovers at exactly the refill rate rather than being
     * held empty by their own retries.
     */
    public static boolean allow(UUID player, Category category) {
        if (player == null || category == null) {
            return false;
        }
        long now = clock.getAsLong();
        Bucket bucket = BUCKETS.computeIfAbsent(new Key(player, category), key -> new Bucket(category, now));
        return bucket.take(now, player, category);
    }

    /** Drops this player's buckets. Called on logout, so the map cannot grow for the server's lifetime. */
    public static void forget(UUID player) {
        if (player != null) {
            BUCKETS.keySet().removeIf(key -> key.player().equals(player));
        }
    }

    /** Whether any bucket is currently held for this player. */
    public static boolean tracked(UUID player) {
        return player != null && BUCKETS.keySet().stream().anyMatch(key -> key.player().equals(player));
    }

    private static final class Bucket {
        private final Category category;
        private double tokens;
        private long lastTick;
        private long refused;
        private long lastWarnTick;

        Bucket(Category category, long now) {
            this.category = category;
            this.tokens = category.burst();
            this.lastTick = now;
            this.lastWarnTick = Long.MIN_VALUE;
        }

        synchronized boolean take(long now, UUID player, Category which) {
            long elapsed = now - lastTick;
            if (elapsed > 0L) {
                tokens = Math.min(category.burst(), tokens + elapsed * category.tokensPerTick());
                lastTick = now;
            } else if (elapsed < 0L) {
                // The clock went backwards (a substituted clock, or a wall clock adjustment). Re-anchor
                // rather than hand out a burst's worth of credit for the gap.
                lastTick = now;
            }
            if (tokens >= 1.0D) {
                tokens -= 1.0D;
                return true;
            }
            refused++;
            if (lastWarnTick == Long.MIN_VALUE || now - lastWarnTick >= WARN_INTERVAL_TICKS) {
                lastWarnTick = now;
                McaCrime.LOGGER.warn("Rate-limited {} requests from {} ({} refused so far)",
                        which, player, refused);
            }
            return false;
        }
    }
}
