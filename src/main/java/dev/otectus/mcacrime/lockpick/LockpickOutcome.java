package dev.otectus.mcacrime.lockpick;

/**
 * How a lockpick session ended. Sent to the client only as a result to display (M3.3).
 *
 * <p>The client is told; it never tells. Upstream's equivalent travels the other way — its screen
 * decides success and the server obeys, which is why a modified client there can free anyone's
 * restraints from any distance.
 */
public enum LockpickOutcome {

    /** The lock opened. */
    SUCCESS,
    /** The meter ran out. */
    FAILED,
    /** The picker asked to stop, or something ended the session for them. */
    CANCELLED
}
