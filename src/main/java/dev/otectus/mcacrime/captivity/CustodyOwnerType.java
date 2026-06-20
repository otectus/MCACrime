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
    /** A jail (lawful custody anchored to a village/position). */
    JAIL,
    /** A village authority holding lawfully without a specific guard. */
    AUTHORITY,
    /** Nobody — released/escaped. */
    NONE;

    public static CustodyOwnerType parse(String name) {
        try {
            return valueOf(name);
        } catch (IllegalArgumentException e) {
            return NONE;
        }
    }
}
