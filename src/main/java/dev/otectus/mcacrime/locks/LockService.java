package dev.otectus.mcacrime.locks;

import dev.otectus.mcacrime.state.world.CrimeWorldData;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The only writer of the {@code locks} table (M3.1).
 *
 * <p>Create, resolve, bind, rekey, lock, unlock, detach and recover, each one a single committed
 * change to {@link CrimeWorldData}. Everything that wants to know whether something is locked asks
 * here; nothing else looks the table up, and nothing else writes it. That is what makes "one canonical
 * lock owner per target group" enforceable rather than hoped for: a second lock on a group this one
 * already owns is refused at {@link #create}, not discovered later by two access checks disagreeing.
 *
 * <p>Positions are canonicalised through {@link LockTargetNormalizer} on the way in and on the way
 * out, so a caller holding the upper half of a door and a caller holding the lower half are asking
 * about the same lock.
 *
 * <p>The position index is a cache and nothing more. It is rebuilt whenever the store instance
 * changes — a world reload hands out a new {@link CrimeWorldData}, and the identity comparison
 * notices — and dropped wholesale on every mutation, because a stale "no lock here" is the one wrong
 * answer that would let a hopper drain a locked safe.
 */
public final class LockService {

    private LockService() {
    }

    /** The cached position → lock id index, and the store it was built from. */
    private static final Map<String, UUID> INDEX = new HashMap<>();
    private static final UUID ABSENT = new UUID(0L, 0L);
    @Nullable
    private static CrimeWorldData indexedStore;

    // --- lookup ------------------------------------------------------------------------------------

    /** The store, or null off a server. */
    @Nullable
    public static CrimeWorldData data(@Nullable MinecraftServer server) {
        return server == null ? null : CrimeWorldData.get(server);
    }

    /** The store behind a level, or null on a client level. */
    @Nullable
    public static CrimeWorldData data(@Nullable Level level) {
        if (!(level instanceof ServerLevel server)) {
            return null;
        }
        return CrimeWorldData.get(server.getServer());
    }

    public static Optional<LockRecord> byId(@Nullable CrimeWorldData data, @Nullable UUID lockId) {
        return data == null ? Optional.empty() : Optional.ofNullable(data.lock(lockId));
    }

    /**
     * The lock on the group containing {@code pos}, if there is one.
     *
     * <p>Canonicalises first, then consults the index, then verifies the record still points where the
     * index said. The verification is what stops a cache that somehow went stale from reporting a lock
     * on a block that no longer has one.
     */
    public static Optional<LockRecord> at(@Nullable CrimeWorldData data, @Nullable Level level,
                                          @Nullable ResourceLocation dimension, @Nullable BlockPos pos) {
        if (data == null || dimension == null || pos == null) {
            return Optional.empty();
        }
        BlockPos partner = LockTargetNormalizer.partner(level, pos);
        LockTarget target = LockTargetNormalizer.canonical(dimension, pos, partner);
        Optional<LockRecord> canonical = at(data, target);
        if (canonical.isPresent() || partner == null) {
            return canonical;
        }

        // A single chest can acquire a partner whose coordinate sorts before its own. Its stored
        // target is then the old single position while both live halves normalize to the new one.
        // Resolve both physical members and move the one lock to the new canonical coordinate.
        List<LockRecord> members = atBlockMembers(data, dimension, pos, partner);
        if (members.size() == 1) {
            return moveTo(data, members.get(0).lockId(), target);
        }
        if (members.size() > 1) {
            // Placement validation rejects this state. If an old/corrupt world already contains it,
            // fail closed by reporting a locked record rather than making the whole group look open.
            return members.stream().filter(LockRecord::locked).findFirst().or(() -> members.stream().findFirst());
        }
        return Optional.empty();
    }

    /** The lock on an already-canonical target. */
    public static Optional<LockRecord> at(@Nullable CrimeWorldData data, @Nullable LockTarget target) {
        if (data == null || target == null || !target.present()) {
            return Optional.empty();
        }
        String key = key(target);
        synchronized (INDEX) {
            if (indexedStore != data) {
                INDEX.clear();
                indexedStore = data;
            }
            UUID cached = INDEX.get(key);
            if (ABSENT.equals(cached)) {
                return Optional.empty();
            }
            if (cached != null) {
                LockRecord record = data.lock(cached);
                if (record != null && LockTargetNormalizer.sameGroup(record.target(), target)) {
                    return Optional.of(record);
                }
                INDEX.remove(key);
            }
        }
        LockRecord found = null;
        for (LockRecord record : data.locks()) {
            if (LockTargetNormalizer.sameGroup(record.target(), target)) {
                found = record;
                break;
            }
        }
        synchronized (INDEX) {
            INDEX.put(key, found == null ? ABSENT : found.lockId());
        }
        return Optional.ofNullable(found);
    }

    /** The lock held by a padlock entity or any other entity target. */
    public static Optional<LockRecord> onEntity(@Nullable CrimeWorldData data, @Nullable UUID entityId) {
        return entityId == null ? Optional.empty() : at(data, LockTarget.entity(entityId));
    }

    // --- mutation ----------------------------------------------------------------------------------

    /**
     * Creates a lock on a target group, or refuses because one is already there.
     *
     * <p>Refusal rather than replacement: two locks on one group is the state the spec forbids, and
     * silently replacing the first would hand the second owner somebody else's door.
     */
    public static Optional<LockRecord> create(@Nullable CrimeWorldData data, @Nullable LockTarget target,
                                              @Nullable UUID owner) {
        if (data == null || target == null || !target.present()) {
            return Optional.empty();
        }
        if (at(data, target).isPresent()) {
            return Optional.empty();
        }
        LockRecord lock = LockRecord.of(UUID.randomUUID(), target, owner);
        if (!data.putLock(lock)) {
            return Optional.empty();
        }
        invalidate();
        return Optional.of(lock);
    }

    /** The lock on this group, creating one when there is none. */
    public static Optional<LockRecord> getOrCreate(@Nullable CrimeWorldData data,
                                                   @Nullable LockTarget target, @Nullable UUID owner) {
        Optional<LockRecord> existing = at(data, target);
        return existing.isPresent() ? existing : create(data, target, owner);
    }

    /**
     * Records that the first key for the current binding revision has been issued.
     *
     * <p>This is a one-way transition, authorized by the persisted owner or by operator authority.
     * It is deliberately separate from key copying: an established lock never becomes copyable just
     * because somebody presents a blank key. Rekeying opens a fresh initialization window.
     */
    public static Optional<LockRecord> initializeKey(@Nullable CrimeWorldData data,
                                                     @Nullable UUID lockId,
                                                     @Nullable UUID actor,
                                                     boolean operatorAuthorised) {
        LockRecord lock = data == null ? null : data.lock(lockId);
        if (lock == null || lock.keyInitialized()
                || (!operatorAuthorised && lock.ownerId().filter(owner -> owner.equals(actor)).isEmpty())) {
            return Optional.empty();
        }
        return commit(data, lock.markKeyInitialized());
    }

    /** Whether this actor may consume the current first-key initialization window. */
    public static boolean mayInitializeKey(@Nullable LockRecord lock, @Nullable UUID actor,
                                           boolean operatorAuthorised) {
        return lock != null && !lock.keyInitialized()
                && (operatorAuthorised
                || lock.ownerId().filter(owner -> owner.equals(actor)).isPresent());
    }

    /** Commits a changed record. Every mutator routes through this one write. */
    private static Optional<LockRecord> commit(@Nullable CrimeWorldData data, @Nullable LockRecord lock) {
        if (data == null || lock == null || !data.putLock(lock)) {
            return Optional.empty();
        }
        invalidate();
        return Optional.of(lock);
    }

    /** Locks or unlocks. Returns the committed record, or empty when nothing changed. */
    public static Optional<LockRecord> setLocked(@Nullable CrimeWorldData data, @Nullable UUID lockId,
                                                 boolean locked) {
        LockRecord lock = data == null ? null : data.lock(lockId);
        if (lock == null) {
            return Optional.empty();
        }
        LockRecord updated = lock.locked(locked);
        return updated == lock ? Optional.of(lock) : commit(data, updated);
    }

    /**
     * Rotates the binding revision: every existing key for this lock stops working.
     *
     * <p>The creative bind breaker's real behaviour, and the only correct one. Rerolling the identity
     * instead — which is what upstream's {@code resetBinding} does — would orphan the block entity, the
     * padlock and every audit reference that names the lock.
     */
    public static Optional<LockRecord> rekey(@Nullable CrimeWorldData data, @Nullable UUID lockId) {
        LockRecord lock = data == null ? null : data.lock(lockId);
        return lock == null ? Optional.empty() : commit(data, lock.rekeyed());
    }

    /** Marks a lock reinforced, or not. */
    public static Optional<LockRecord> setReinforced(@Nullable CrimeWorldData data, @Nullable UUID lockId,
                                                     boolean reinforced) {
        LockRecord lock = data == null ? null : data.lock(lockId);
        if (lock == null) {
            return Optional.empty();
        }
        LockRecord updated = lock.reinforced(reinforced);
        return updated == lock ? Optional.of(lock) : commit(data, updated);
    }

    /** Points a lock at a different target, keeping its binding so every key still works. */
    public static Optional<LockRecord> moveTo(@Nullable CrimeWorldData data, @Nullable UUID lockId,
                                              @Nullable LockTarget target) {
        LockRecord lock = data == null ? null : data.lock(lockId);
        if (lock == null) {
            return Optional.empty();
        }
        if (target != null && target.present() && at(data, target)
                .filter(other -> !other.lockId().equals(lockId)).isPresent()) {
            return Optional.empty(); // somebody else's group; one owner per group
        }
        return commit(data, lock.movedTo(target));
    }

    /** Every distinct lock stored on either exact block position, without canonicalizing either. */
    public static List<LockRecord> atBlockMembers(@Nullable CrimeWorldData data,
                                                  @Nullable ResourceLocation dimension,
                                                  @Nullable BlockPos first,
                                                  @Nullable BlockPos second) {
        Map<UUID, LockRecord> found = new LinkedHashMap<>();
        if (data == null || dimension == null) {
            return List.of();
        }
        for (LockRecord record : data.locks()) {
            if (record.target().kind() != LockTarget.Kind.BLOCK
                    || !dimension.equals(record.target().dimension())) {
                continue;
            }
            BlockPos stored = record.target().pos();
            if ((first != null && first.equals(stored)) || (second != null && second.equals(stored))) {
                found.put(record.lockId(), record);
            }
        }
        return List.copyOf(found.values());
    }

    /**
     * Moves the sole lock on a two-block group to its current canonical coordinate.
     *
     * @return empty when the group is unowned or conflicting; a conflict is never silently merged
     */
    public static Optional<LockRecord> reconcileBlockGroup(@Nullable CrimeWorldData data,
                                                           @Nullable ResourceLocation dimension,
                                                           @Nullable BlockPos first,
                                                           @Nullable BlockPos second) {
        List<LockRecord> members = atBlockMembers(data, dimension, first, second);
        if (members.size() != 1 || dimension == null || first == null) {
            return Optional.empty();
        }
        LockTarget canonical = LockTargetNormalizer.canonical(dimension, first, second);
        LockRecord lock = members.get(0);
        if (LockTargetNormalizer.sameGroup(lock.target(), canonical)) {
            return Optional.of(lock);
        }
        return moveTo(data, lock.lockId(), canonical);
    }

    /**
     * Detaches a lock from its target without forgetting it.
     *
     * <p>Idempotent, and that matters: a block broken by a player, by an explosion and by a piston in
     * the same tick must detach the same padlock once. A detached lock keeps its row because keys are
     * still bound to it and an operator has to be able to see what they open — {@link #forget} is the
     * deliberate, rarer act of dropping the row entirely.
     */
    public static boolean detach(@Nullable CrimeWorldData data, @Nullable UUID lockId) {
        LockRecord lock = data == null ? null : data.lock(lockId);
        if (lock == null || !lock.target().present()) {
            return false;
        }
        return commit(data, lock.movedTo(LockTarget.none())).isPresent();
    }

    /** Drops a lock row entirely. Operator recovery only. */
    public static boolean forget(@Nullable CrimeWorldData data, @Nullable UUID lockId) {
        if (data == null || !data.removeLock(lockId)) {
            return false;
        }
        invalidate();
        return true;
    }

    /**
     * Every lock whose target is gone: the recovery route for an abandoned lock.
     *
     * <p>Named rather than swept automatically. A lock whose block is unloaded is not an orphan, and a
     * sweep that could not tell the difference would delete locks for chunks nobody had visited yet.
     */
    public static java.util.List<LockRecord> orphans(@Nullable CrimeWorldData data) {
        java.util.List<LockRecord> orphans = new java.util.ArrayList<>();
        if (data == null) {
            return orphans;
        }
        for (LockRecord lock : data.locks()) {
            if (!lock.target().present()) {
                orphans.add(lock);
            }
        }
        return orphans;
    }

    /** Drops the position index. Called on every mutation, and safe to call at any time. */
    public static void invalidate() {
        synchronized (INDEX) {
            INDEX.clear();
            indexedStore = null;
        }
    }

    private static String key(LockTarget target) {
        return switch (target.kind()) {
            case BLOCK -> target.dimension() + "@" + target.pos();
            case ENTITY -> "entity:" + target.entityId();
            case NONE -> "none";
        };
    }
}
