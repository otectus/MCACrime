package dev.otectus.mcacrime.lockpick;

import dev.otectus.mcacrime.restraint.RestraintSlot;
import net.minecraft.core.BlockPos;

import org.jetbrains.annotations.Nullable;
import java.util.Optional;
import java.util.UUID;

/**
 * What a lockpick session is working on, pinned at the moment it opened (§3.5).
 *
 * <p>The identity is the point. A block target carries the {@code lockId} and the binding revision
 * rather than only coordinates, so a lock replaced at the same position mid-pick cannot be opened by
 * the session that was aimed at its predecessor. A restraint target carries the subject, the slot and
 * the instance id, so removing and reapplying cuffs during a pick does not hand the picker the new
 * pair.
 *
 * @param kind            what sort of thing is being picked
 * @param lockId          the lock identity, for a block or padlock
 * @param bindingRevision that lock's binding revision when the session opened
 * @param pos             the canonical block position, for a block target
 * @param entityId        the padlock entity, for a padlock target
 * @param subject         the restrained subject, for a restraint target
 * @param slot            the restrained slot, for a restraint target
 * @param instanceId      the worn instance's own id, for a restraint target
 */
public record LockpickTarget(Kind kind, @Nullable UUID lockId, long bindingRevision,
                             @Nullable BlockPos pos, @Nullable UUID entityId, @Nullable UUID subject,
                             @Nullable RestraintSlot slot, @Nullable UUID instanceId) {

    public enum Kind {
        /** A cell door, a safe or any other lockable block. */
        BLOCK,
        /** A padlock entity hanging on a block. */
        PADLOCK,
        /** Something worn by somebody. */
        RESTRAINT
    }

    public static LockpickTarget block(UUID lockId, long bindingRevision, BlockPos pos) {
        return new LockpickTarget(Kind.BLOCK, lockId, bindingRevision, pos.immutable(), null, null,
                null, null);
    }

    public static LockpickTarget padlock(UUID lockId, long bindingRevision, UUID entityId, BlockPos pos) {
        return new LockpickTarget(Kind.PADLOCK, lockId, bindingRevision,
                pos == null ? null : pos.immutable(), entityId, null, null, null);
    }

    public static LockpickTarget restraint(UUID subject, RestraintSlot slot, UUID instanceId) {
        return new LockpickTarget(Kind.RESTRAINT, null, 0L, null, null, subject, slot, instanceId);
    }

    public Optional<UUID> lock() {
        return Optional.ofNullable(lockId);
    }

    public Optional<BlockPos> position() {
        return Optional.ofNullable(pos);
    }

    /** The UUID a {@code Session} tracks as its target, so the registry can cancel by subject. */
    @Nullable
    public UUID sessionTarget() {
        return switch (kind) {
            case BLOCK -> lockId;
            case PADLOCK -> entityId;
            case RESTRAINT -> subject;
        };
    }
}
