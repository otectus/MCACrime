package dev.otectus.mcacrime.locks;

import dev.otectus.mcacrime.lockpick.LockpickProfile;
import net.minecraft.network.chat.Component;

import java.util.Optional;
import java.util.UUID;

/**
 * Something that can carry one lock: a cell door, a safe, a padlock entity (M3.1).
 *
 * <p>Three of the four things a lock interaction needs — which lock, where it is, and what to call it
 * — with the fourth (is it locked) deliberately absent, because that answer belongs to the
 * {@code locks} table and asking the holder for it would be the second copy this design exists to
 * avoid.
 *
 * <p>Implemented by block entities and by an entity, which is why it is an interface rather than a
 * base class: a {@code BlockEntity} and a {@code HangingEntity} have no common ancestor worth using.
 */
public interface LockHolder {

    /** The lock this holder carries, if it has ever been bound to one. */
    Optional<UUID> lockId();

    /** Binds this holder to a lock. Must refuse to replace a different one. */
    boolean bindLock(UUID lockId);

    /** The canonical target this holder's lock sits on. */
    LockTarget lockTarget();

    /** What to call this thing in a message to a player. */
    Component lockDisplayName();

    /** How hard it is to pick. */
    LockpickProfile pickProfile(boolean reinforced);

    /** Called after this holder's lock changed, so caches and open screens can be dealt with. */
    default void onLockChanged() {
    }

    /** Whether a blank key may bind to this holder at all right now. */
    default boolean bindable() {
        return lockId().isEmpty();
    }
}
