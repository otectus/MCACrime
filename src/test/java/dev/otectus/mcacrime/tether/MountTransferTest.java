package dev.otectus.mcacrime.tether;

import dev.otectus.mcacrime.restraint.RestrictionPolicy;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Not every clicked entity is a vehicle, and a leg restraint blocks exactly two things (0.7.5 M4.4).
 *
 * <p>The source's forced seating is one line: right-clicking <em>any</em> non-player entity while
 * escorting calls {@code startRiding} on it ({@code event/ModServerEvents.java:320-334}). These
 * assertions pin the validation that replaces it, and the boundary of the restriction: voluntary
 * steering and the ordinary dismount route are blocked, and an emergency release never is — a
 * restraint that could trap somebody inside a deleted boat would be a restraint that deletes players.
 */
class MountTransferTest {

    private static MountTransfer.Refusal check(boolean enabled, boolean subject, boolean vehicle,
                                               boolean carries, boolean room, boolean sameDim,
                                               boolean same, boolean loop) {
        return MountTransfer.check(enabled, subject, vehicle, carries, room, sameDim, same, loop);
    }

    private static MountTransfer.Refusal ordinary() {
        return check(true, true, true, true, true, true, false, false);
    }

    // ---------------------------------------------------------------- validation

    @Test
    void anOrdinaryTransferStands() {
        assertEquals(MountTransfer.Refusal.NONE, ordinary());
    }

    @Test
    void somethingNobodyRidesIsRefused() {
        assertEquals(MountTransfer.Refusal.NOT_A_VEHICLE,
                check(true, true, true, false, true, true, false, false));
    }

    @Test
    void aFullVehicleIsRefused() {
        assertEquals(MountTransfer.Refusal.VEHICLE_FULL,
                check(true, true, true, true, false, true, false, false));
    }

    @Test
    void anotherDimensionIsRefused() {
        assertEquals(MountTransfer.Refusal.WRONG_DIMENSION,
                check(true, true, true, true, true, false, false, false));
    }

    @Test
    void anEntityCannotRideItselfOrItsOwnPassenger() {
        assertEquals(MountTransfer.Refusal.SAME_ENTITY,
                check(true, true, true, true, true, true, true, false));
        assertEquals(MountTransfer.Refusal.WOULD_LOOP,
                check(true, true, true, true, true, true, false, true));
    }

    @Test
    void identityIsCheckedBeforeCapability() {
        // A loop is refused even when the "vehicle" is not one: the answer a player needs is why the
        // transfer is impossible, not which of two impossibilities was noticed first.
        assertEquals(MountTransfer.Refusal.WOULD_LOOP,
                check(true, true, true, false, true, true, false, true));
    }

    @Test
    void theMasterSwitchOutranksEverything() {
        assertEquals(MountTransfer.Refusal.DISABLED,
                check(false, true, true, true, true, true, false, false));
    }

    @Test
    void aMissingSubjectOrVehicleIsNamedRatherThanIgnored() {
        assertEquals(MountTransfer.Refusal.NO_SUBJECT,
                check(true, false, true, true, true, true, false, false));
        assertEquals(MountTransfer.Refusal.NO_VEHICLE,
                check(true, true, false, true, true, true, false, false));
    }

    @Test
    void everyRefusalHasAMessage() {
        for (MountTransfer.Refusal refusal : MountTransfer.Refusal.values()) {
            assertTrue(MountTransfer.messageKey(refusal).startsWith("mcacrime."), refusal.name());
        }
        assertTrue(MountTransfer.messageKey(null).startsWith("mcacrime."));
    }

    // ---------------------------------------------------------------- getting out again

    private static RestrictionPolicy legsBound() {
        return RestrictionPolicy.deny().vehicleControl().build();
    }

    @Test
    void aLegRestraintBlocksVoluntaryDismountAndSteering() {
        assertFalse(MountTransfer.mayDismount(legsBound(), false));
        assertFalse(MountTransfer.maySteer(legsBound()));
    }

    @Test
    void anEmergencyReleaseAlwaysWins() {
        assertTrue(MountTransfer.mayDismount(legsBound(), true),
                "a vehicle being destroyed, removed or taken to another dimension lets them out");
    }

    @Test
    void anUnrestrainedPassengerGetsOutNormally() {
        assertTrue(MountTransfer.mayDismount(RestrictionPolicy.unrestricted(), false));
        assertTrue(MountTransfer.maySteer(RestrictionPolicy.unrestricted()));
        assertTrue(MountTransfer.mayDismount((RestrictionPolicy) null, false),
                "no policy at all is no restriction at all");
        assertTrue(MountTransfer.maySteer(null));
    }
}
