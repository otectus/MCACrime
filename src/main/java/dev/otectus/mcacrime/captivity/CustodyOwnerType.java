package dev.otectus.mcacrime.captivity;

/**
 * Who holds a captive (spec §2.3). Drives the {@link CustodyOwner} payload and, with the {@code lawful}
 * flag on the {@link CustodyRecord}, the jail-vs-kidnapping rules. {@link #parse} is fail-safe so a
 * hand-edited or partial save can never crash the load (defaults to {@link #NONE}).
 */
public enum CustodyOwnerType {
    /** An unlawful kidnapper (a player or NPC). */
    KIDNAPPER,
    /** A guard performing a lawful arrest. */
    GUARD,
    /**
     * A player collecting a bounty on an outlaw (0.5.1).
     *
     * <p>Lawful, and that is the whole reason it is not {@link #KIDNAPPER}: restraining somebody the
     * law already authorises force against, while a bounty stands on them, is a citizen's arrest and
     * must not commit the {@code kidnap} crime against the person doing it. Every other holder of a
     * lawful record is the law itself, so the distinction is not cosmetic — a hunter is owed money on
     * delivery and a guard is not.
     */
    BOUNTY_HUNTER,
    /** A jail (lawful custody anchored to a village/position). */
    JAIL,
    /** A village authority holding lawfully without a specific guard. */
    AUTHORITY,
    /** Nobody — released/escaped. */
    NONE;

    /** Fail-safe: a null, blank, or unrecognised name reads as {@link #NONE} rather than throwing. */
    public static CustodyOwnerType parse(String name) {
        if (name == null || name.isBlank()) {
            return NONE;
        }
        try {
            return valueOf(name.trim());
        } catch (IllegalArgumentException e) {
            return NONE;
        }
    }
}
