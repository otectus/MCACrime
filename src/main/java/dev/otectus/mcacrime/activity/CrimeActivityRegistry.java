package dev.otectus.mcacrime.activity;

import dev.otectus.mcacrime.McaCrimeConfig;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;

import org.jetbrains.annotations.Nullable;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Who is currently acting on which villager, and on whose authority.
 *
 * <p>MCA: Crime already had one of these: {@link dev.otectus.mcacrime.enforcement.LawHold}, a deadline
 * per responder that stopped the reaction ticker from steering a guard mid-arrest. It worked, and it
 * answered exactly one question — "is law busy with this entity?" — for exactly one consumer. Once a
 * settlement companion is in the world there are several systems sending the same villager to work,
 * to bed and into a reaction animation, and the useful question becomes "who owns this villager right
 * now, how strongly, and what may still happen to them?".
 *
 * <p>So this is a generalisation of {@code LawHold} rather than a replacement: {@code LawHold} keeps
 * its own map and its own meaning and becomes one producer among several, and everything that used to
 * ask it still gets the same answer.
 *
 * <h2>Generations</h2>
 *
 * <p>Every claim carries a monotonic stamp. Release and renew take that stamp and are no-ops when it
 * is stale, which is what makes the lifecycle safe against the failure this whole layer exists to
 * prevent: an escort that ends two ticks after a custody transfer must not clear the custody claim,
 * and a reaction controller that shut down must not restore a walk target over the arrest that
 * pre-empted it. Without a generation, "release what I took" and "release whatever is there" are the
 * same call, and the second one is wrong.
 *
 * <h2>Leases</h2>
 *
 * <p>Claims expire rather than being cleared. A subsystem whose entity unloaded, whose player logged
 * out or that simply threw halfway through is the ordinary case, not the exceptional one, and a claim
 * that needed an explicit release to end would strand a villager for the rest of the session. The
 * lease is {@code townstead.activityLeaseTicks} (default 40 = four guard scans); a producer that is
 * still working renews it on its own scan.
 *
 * <p>Memory-only, and never persisted, for the same reason {@code LawHold} is not: a claim describes
 * the current few seconds, and a restart has no business inheriting one.
 */
public final class CrimeActivityRegistry {

    /** The lease used when the config is unavailable, which is every unit test and the dedicated CLI. */
    public static final int DEFAULT_LEASE_TICKS = 40;

    /** Not a generation. Returned by {@link #claim} when a stronger claim refused it. */
    public static final long REFUSED = 0L;

    private static final Map<UUID, CrimeActivityView> ACTIVE = new ConcurrentHashMap<>();

    private static final AtomicLong GENERATIONS = new AtomicLong();

    /**
     * The last game time anybody swept at.
     *
     * <p>Carried so {@link #activeFor(UUID)} can answer without a game time. Deep hooks — a behaviour
     * gate inside {@code Brain.tick}, an injected handler in somebody else's ticker — have the entity
     * and nothing else, and threading a clock down to them would mean changing signatures that are not
     * MCA: Crime's to change. The value is refreshed by every {@link #sweep(long)}, which the guard
     * scan and the reaction ticker both call, so it is never more than a few ticks behind.
     */
    private static volatile long lastSweep;

    private CrimeActivityRegistry() {
    }

    /** How long a fresh claim lasts. Falls back to {@link #DEFAULT_LEASE_TICKS} with no config loaded. */
    public static int leaseTicks() {
        try {
            return McaCrimeConfig.COMMON.townsteadActivityLeaseTicks.get();
        } catch (Throwable t) {
            return DEFAULT_LEASE_TICKS;
        }
    }

    /**
     * Takes or extends a claim on this villager.
     *
     * <p>Three outcomes. A free villager, or one held by a claim this one at least matches in
     * authority, is claimed at a fresh generation. The same owner re-claiming the same kind renews
     * instead, keeping its generation — that is what lets a producer call this every scan without
     * invalidating the release it is holding. A villager held by a <em>stronger</em> claim is refused,
     * and the caller gets {@link #REFUSED}.
     *
     * @return the generation to release with, or {@link #REFUSED}
     */
    public static long claim(@Nullable UUID entity, @Nullable ResourceLocation dimension,
                             CrimeActivityView.Kind kind, String owner, long now) {
        return claim(entity, dimension, kind, owner, now, leaseTicks());
    }

    /** As {@link #claim(UUID, ResourceLocation, CrimeActivityView.Kind, String, long)}, with an explicit lease. */
    public static long claim(@Nullable UUID entity, @Nullable ResourceLocation dimension,
                             CrimeActivityView.Kind kind, String owner, long now, int leaseTicks) {
        if (entity == null || kind == null) {
            return REFUSED;
        }
        long expiry = now + Math.max(1, leaseTicks);
        CrimeActivityView existing = ACTIVE.get(entity);
        if (existing != null && existing.live(now)) {
            if (existing.kind() == kind && existing.owner().equals(owner)) {
                ACTIVE.put(entity, existing.renewedUntil(expiry));
                return existing.generation();
            }
            if (!kind.authority().atLeast(existing.authority())) {
                return REFUSED;
            }
        }
        long generation = GENERATIONS.incrementAndGet();
        ACTIVE.put(entity, new CrimeActivityView(entity, dimension, kind, owner, generation,
                kind.authority(), OperationPolicy.allowed(kind), expiry));
        return generation;
    }

    /** The convenience form for a producer holding the entity. */
    public static long claim(@Nullable Entity entity, CrimeActivityView.Kind kind, String owner, long now) {
        if (entity == null) {
            return REFUSED;
        }
        ResourceLocation dimension = entity.level() == null ? null : entity.level().dimension().location();
        return claim(entity.getUUID(), dimension, kind, owner, now);
    }

    /**
     * Extends a claim the caller still holds.
     *
     * @return false when the claim lapsed or was taken over, which tells the caller its ownership is
     *         gone and it must stop writing to the villager
     */
    public static boolean renew(@Nullable UUID entity, long generation, long now) {
        return renew(entity, generation, now, leaseTicks());
    }

    /** As {@link #renew(UUID, long, long)}, with an explicit lease. */
    public static boolean renew(@Nullable UUID entity, long generation, long now, int leaseTicks) {
        if (entity == null || generation == REFUSED) {
            return false;
        }
        CrimeActivityView existing = ACTIVE.get(entity);
        if (existing == null || existing.generation() != generation || !existing.live(now)) {
            return false;
        }
        ACTIVE.put(entity, existing.renewedUntil(now + Math.max(1, leaseTicks)));
        return true;
    }

    /**
     * Extends whatever claim is live on this villager, without owning it.
     *
     * <p>What a <em>consumer</em> calls. The producers above take and release claims; the navigation
     * layer only ever sees "somebody is steering this villager and has just issued another order", and
     * has no generation to renew with. Extending a lease is not a takeover — it cannot change who owns
     * the villager or what is allowed — so it is safe without one, and it is what stops a long escort
     * from lapsing between two guard scans.
     *
     * @return true when a live claim was extended
     */
    public static boolean touch(@Nullable UUID entity, long now) {
        if (entity == null || ACTIVE.isEmpty()) {
            return false;
        }
        CrimeActivityView existing = ACTIVE.get(entity);
        if (existing == null || !existing.live(now)) {
            return false;
        }
        ACTIVE.put(entity, existing.renewedUntil(now + Math.max(1, leaseTicks())));
        return true;
    }

    /**
     * Drops a claim, but only the caller's own.
     *
     * <p>A stale generation is a no-op and not an error: finishing late is the normal way an
     * enforcement action ends, and the whole point of the stamp is that a late finisher cannot clear
     * the claim that replaced it.
     *
     * @return true when this call actually released something
     */
    public static boolean release(@Nullable UUID entity, long generation) {
        if (entity == null || generation == REFUSED) {
            return false;
        }
        CrimeActivityView existing = ACTIVE.get(entity);
        if (existing == null || existing.generation() != generation) {
            return false;
        }
        return ACTIVE.remove(entity, existing);
    }

    /**
     * Drops a claim the caller owns by identity rather than by number.
     *
     * <p>For producers whose state is a recurring judgement rather than a transaction — a thief who is
     * mid-action this think and idle the next, a session that is simply gone. They re-assert the same
     * kind under the same owner token every pass, so the generation is not theirs to track, and
     * matching on {@code kind} plus {@code owner} gives them the same safety: a claim somebody else
     * took in the meantime does not match, and is left alone.
     *
     * @return true when this call actually released something
     */
    public static boolean releaseOwned(@Nullable UUID entity, CrimeActivityView.Kind kind, String owner) {
        if (entity == null || kind == null) {
            return false;
        }
        CrimeActivityView existing = ACTIVE.get(entity);
        if (existing == null || existing.kind() != kind || !existing.owner().equals(owner)) {
            return false;
        }
        return ACTIVE.remove(entity, existing);
    }

    /**
     * Drops whatever claim this villager has, regardless of generation.
     *
     * <p>For the two places where that is the honest thing to do: server stop, and a villager whose
     * death or removal makes every claim on them meaningless. Ordinary completion uses
     * {@link #release(UUID, long)}.
     */
    public static void forget(@Nullable UUID entity) {
        if (entity != null) {
            ACTIVE.remove(entity);
        }
    }

    /** The live claim on this villager, if any, as of the last sweep. */
    public static Optional<CrimeActivityView> activeFor(@Nullable UUID entity) {
        return activeFor(entity, lastSweep);
    }

    /** The live claim on this villager at {@code now}, if any. Expired claims never answer. */
    public static Optional<CrimeActivityView> activeFor(@Nullable UUID entity, long now) {
        if (entity == null || ACTIVE.isEmpty()) {
            return Optional.empty();
        }
        CrimeActivityView existing = ACTIVE.get(entity);
        return existing != null && existing.live(now) ? Optional.of(existing) : Optional.empty();
    }

    /** Whether anything at all is claimed. The cheap first question for a per-tick hook. */
    public static boolean isEmpty() {
        return ACTIVE.isEmpty();
    }

    /** Whether MCA: Crime is acting on this villager right now. */
    public static boolean isClaimed(@Nullable UUID entity) {
        return activeFor(entity).isPresent();
    }

    /** Whether MCA: Crime is acting on this villager at {@code now}. */
    public static boolean isClaimed(@Nullable UUID entity, long now) {
        return activeFor(entity, now).isPresent();
    }

    /** The generation currently held on this villager, or {@link #REFUSED} when nothing is. */
    public static long generationOf(@Nullable UUID entity, long now) {
        return activeFor(entity, now).map(CrimeActivityView::generation).orElse(REFUSED);
    }

    /**
     * Whether {@code operation} may start on this villager right now.
     *
     * <p>The question every yielding hook asks, and the reason the answer is here rather than at each
     * hook: an unclaimed villager must behave exactly as they do with MCA: Crime not installed, so the
     * default is always "yes".
     */
    public static boolean permits(@Nullable UUID entity, @Nullable CrimeActivityOperation operation) {
        if (entity == null || operation == null || ACTIVE.isEmpty()) {
            return true;
        }
        return activeFor(entity).map(claim -> !claim.requiresYield(operation)).orElse(true);
    }

    /** Everything allowed on this villager right now; the full set when nothing is claimed. */
    public static Set<CrimeActivityOperation> allowedFor(@Nullable UUID entity) {
        return activeFor(entity).map(CrimeActivityView::allowedOperations)
                .orElseGet(() -> OperationPolicy.allowed(null));
    }

    /**
     * Drops lapsed claims and moves the clock {@link #activeFor(UUID)} reads.
     *
     * <p>Called from the guard scan and the reaction ticker, both already throttled. Cheap when empty,
     * which is the usual state.
     */
    public static void sweep(long now) {
        lastSweep = now;
        if (!ACTIVE.isEmpty()) {
            ACTIVE.values().removeIf(claim -> !claim.live(now));
        }
    }

    /** Drops every claim. Called on server stop, beside {@code LawHold.clearAll()}. */
    public static void clearAll() {
        ACTIVE.clear();
        lastSweep = 0L;
    }

    /** Exposed for {@code /crime debug}: how many villagers MCA: Crime is acting on. */
    public static int claimedCount() {
        return ACTIVE.size();
    }
}
