package dev.otectus.mcacrime.tether;

import dev.otectus.mcacrime.McaCrimeConfig;
import dev.otectus.mcacrime.restraint.RestraintService;
import dev.otectus.mcacrime.restraint.RestrictionPolicy;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.vehicle.Boat;
import net.minecraft.world.phys.Vec3;

import org.jetbrains.annotations.Nullable;

/**
 * Putting somebody into a seat they did not choose, and letting them out again (0.7.5 M4.4).
 *
 * <p>The source's version is one line: right-clicking <em>any</em> non-player entity while escorting
 * calls {@code startRiding} on it and hopes ({@code event/ModServerEvents.java:320-334}). That is how
 * a prisoner ends up riding a bat, a dropped item's holder or a boss. This validates first: the
 * target has to be something that carries passengers, it has to have room, it has to be in the same
 * dimension, it must not be the subject themselves or something the subject is already carrying, and
 * there has to be a position to put them in.
 *
 * <p>The restriction side is the specification's: a leg restraint blocks <b>voluntary steering</b> and
 * the <b>ordinary dismount route</b>, and nothing else. Gravity still applies, the vehicle still moves,
 * external impulses still land, and an emergency release — the vehicle being destroyed, removed or
 * taken to another dimension — still lets the passenger out somewhere safe. A restraint that could
 * trap somebody inside a deleted boat would be a restraint that deletes players.
 */
public final class MountTransfer {

    /** Why a forced mount was refused. Each is a distinct message rather than a silent failure. */
    public enum Refusal {
        NONE,
        DISABLED,
        NO_SUBJECT,
        NO_VEHICLE,
        NOT_A_VEHICLE,
        VEHICLE_FULL,
        WRONG_DIMENSION,
        SAME_ENTITY,
        WOULD_LOOP
    }

    private MountTransfer() {
    }

    /** The lang key that explains one refusal, enumerated by the coverage test. */
    public static String messageKey(@Nullable Refusal refusal) {
        if (refusal == null) {
            return "mcacrime.transport.mount_refused";
        }
        return switch (refusal) {
            case NONE -> "mcacrime.transport.mounted";
            case DISABLED -> "mcacrime.transport.mount_disabled";
            case NO_SUBJECT -> "mcacrime.transport.no_subject";
            case NO_VEHICLE, NOT_A_VEHICLE -> "mcacrime.transport.not_a_vehicle";
            case VEHICLE_FULL -> "mcacrime.transport.vehicle_full";
            case WRONG_DIMENSION -> "mcacrime.transport.wrong_dimension";
            case SAME_ENTITY, WOULD_LOOP -> "mcacrime.transport.mount_refused";
        };
    }

    /** {@code transport.forcedMountingEnabled}, defaulting to the documented true. */
    public static boolean enabled() {
        try {
            return McaCrimeConfig.COMMON.forcedMountingEnabled.get();
        } catch (IllegalStateException | NullPointerException notLoaded) {
            return true;
        }
    }

    /**
     * Whether this transfer would stand, from facts alone.
     *
     * <p>Pure, and the reason it is: "every clicked entity is a vehicle" is the source's bug, and the
     * correction is a list of conditions that can be read and asserted without spawning a boat.
     */
    public static Refusal check(boolean enabled, boolean subjectPresent, boolean vehiclePresent,
                                boolean vehicleCarriesPassengers, boolean hasRoom,
                                boolean sameDimension, boolean sameEntity, boolean wouldLoop) {
        if (!enabled) {
            return Refusal.DISABLED;
        }
        if (!subjectPresent) {
            return Refusal.NO_SUBJECT;
        }
        if (!vehiclePresent) {
            return Refusal.NO_VEHICLE;
        }
        if (sameEntity) {
            return Refusal.SAME_ENTITY;
        }
        if (wouldLoop) {
            return Refusal.WOULD_LOOP; // a passenger cannot ride their own passenger
        }
        if (!vehicleCarriesPassengers) {
            return Refusal.NOT_A_VEHICLE;
        }
        if (!sameDimension) {
            return Refusal.WRONG_DIMENSION;
        }
        return hasRoom ? Refusal.NONE : Refusal.VEHICLE_FULL;
    }

    /**
     * Whether {@code vehicle} is the sort of thing a passenger rides.
     *
     * <p>Asked of the entity rather than of a list of types: vanilla already answers it through
     * {@code canBeRidden}/{@code showVehicleHealth}-style capability, and a hard-coded list would
     * refuse every modded mount in the game.
     */
    public static boolean carriesPassengers(@Nullable Entity vehicle) {
        if (vehicle == null || !vehicle.isAlive()) {
            return false;
        }
        if (vehicle instanceof Boat || vehicle instanceof net.minecraft.world.entity.vehicle.AbstractMinecart) {
            return true;
        }
        return vehicle instanceof LivingEntity living && living.isAlive()
                && vehicle.canBeCollidedWith()
                && vehicle.getType().getCategory() != net.minecraft.world.entity.MobCategory.MISC;
    }

    /** Whether {@code vehicle} has room for one more, without asking it to say no first. */
    public static boolean hasRoom(@Nullable Entity vehicle) {
        if (vehicle == null) {
            return false;
        }
        int limit = vehicle instanceof Boat ? 2 : 1;
        return vehicle.getPassengers().size() < limit;
    }

    /** Whether putting {@code subject} on {@code vehicle} would make a ring of passengers. */
    public static boolean wouldLoop(@Nullable Entity subject, @Nullable Entity vehicle) {
        if (subject == null || vehicle == null) {
            return false;
        }
        Entity cursor = vehicle;
        for (int depth = 0; depth < 8 && cursor != null; depth++) {
            if (cursor == subject) {
                return true;
            }
            cursor = cursor.getVehicle();
        }
        return vehicle.hasPassenger(subject) || subject.hasPassenger(vehicle);
    }

    /**
     * Puts {@code subject} into {@code vehicle}, if every condition holds.
     *
     * <p>Works for players and NPCs alike — there is nothing player-specific in it, which is what the
     * specification asks for and what the source's player-only capability cannot do.
     */
    public static Refusal force(@Nullable LivingEntity subject, @Nullable Entity vehicle) {
        Refusal refusal = check(enabled(), subject != null && subject.isAlive(), vehicle != null,
                carriesPassengers(vehicle), hasRoom(vehicle),
                subject != null && vehicle != null
                        && subject.level().dimension().equals(vehicle.level().dimension()),
                subject != null && subject == vehicle, wouldLoop(subject, vehicle));
        if (refusal != Refusal.NONE) {
            return refusal;
        }
        return subject.startRiding(vehicle, true) ? Refusal.NONE : Refusal.VEHICLE_FULL;
    }

    // --- getting out again -----------------------------------------------------------------------------

    /**
     * Whether the subject may dismount of their own accord.
     *
     * <p>Pure. The restriction is the only thing that stops them, and an emergency always wins: a
     * vehicle that is being destroyed, removed or taken to another dimension releases its passenger
     * whatever their legs are wearing.
     */
    public static boolean mayDismount(@Nullable RestrictionPolicy policy, boolean emergency) {
        return emergency || policy == null || policy.dismount();
    }

    /** Whether the subject may steer what they are riding. */
    public static boolean maySteer(@Nullable RestrictionPolicy policy) {
        return policy == null || policy.steerVehicle();
    }

    /** The live answer for one subject, read from their composed restriction profile. */
    public static boolean mayDismount(@Nullable Entity subject, boolean emergency) {
        return mayDismount(RestraintService.policy(subject), emergency);
    }

    /**
     * Lets a passenger out because their vehicle is going away.
     *
     * <p>The emergency route. A safe exit is a position the subject fits in: the vehicle's own
     * position is tried first, then a step in each horizontal direction, and failing all of that they
     * are left where the vehicle was — above ground, not inside it.
     */
    public static Vec3 emergencyExit(@Nullable Entity subject, @Nullable Entity vehicle) {
        if (vehicle == null) {
            return subject == null ? Vec3.ZERO : subject.position();
        }
        Vec3 base = vehicle.position();
        if (subject == null) {
            return base;
        }
        Vec3[] candidates = {
                base,
                base.add(1.0D, 0.0D, 0.0D), base.add(-1.0D, 0.0D, 0.0D),
                base.add(0.0D, 0.0D, 1.0D), base.add(0.0D, 0.0D, -1.0D),
                base.add(0.0D, 1.0D, 0.0D)};
        for (Vec3 candidate : candidates) {
            var box = subject.getBoundingBox().move(candidate.subtract(subject.position()));
            if (subject.level().noCollision(subject, box)) {
                return candidate;
            }
        }
        return base;
    }

    /** Releases a passenger safely. Called when their vehicle dies, unloads or changes dimension. */
    public static void release(@Nullable Entity subject, @Nullable Entity vehicle) {
        if (subject == null) {
            return;
        }
        Vec3 exit = emergencyExit(subject, vehicle);
        subject.stopRiding();
        subject.teleportTo(exit.x, exit.y, exit.z);
    }
}
