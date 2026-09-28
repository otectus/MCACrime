package dev.otectus.mcacrime.restraint;

/**
 * Why a {@link Session} ended before it finished.
 *
 * <p>The list is the cancellation matrix from §3.5, written out so that a handler cannot end a
 * session for a reason nobody named, and so that the reasons can be asserted one by one.
 */
public enum SessionCancelCause {

    DEATH,
    LOGOUT,
    DIMENSION_CHANGE,
    /** The actor or the target moved out of range. */
    RANGE_LOST,
    /** The target entity, block or instance is gone. */
    TARGET_REMOVED,
    /** Something else is at that identity now: a new lock, a new pair of cuffs, a new custody. */
    TARGET_REPLACED,
    /** Damage that the configured policy says interrupts work. */
    DAMAGE,
    /** The actor is no longer holding the item the session was opened with. */
    ITEM_CHANGED,
    /** The legal custody the session was scoped to was replaced or ended. */
    CUSTODY_REPLACED,
    MENU_CLOSED,
    /** The session outlived its expiry without being renewed. */
    EXPIRED,
    /** The same actor opened another session; one actor does one thing at a time. */
    REPLACED,
    /** The actor asked to stop. */
    CANCELLED,
    /** The server is shutting down, or the store went read-only. */
    SERVER_STOPPING,
    /**
     * The common config was reloaded under a running session (0.7.5 M7.1).
     *
     * <p>Its own reason rather than {@link #CANCELLED}: nobody asked for this, and an operator
     * reading a log needs to see that their own reload is what ended somebody's work.
     */
    CONFIG_RELOADED
}
