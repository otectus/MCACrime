package dev.otectus.mcacrime.ledger;

/**
 * The lifecycle state of a {@link CrimeRecord} (spec §2.2). Serious crimes keep consequences until
 * explicitly resolved — Karma recovering to Grey does not clear an {@code UNRESOLVED} murder record.
 * Only {@link #UNRESOLVED} is produced in this phase; the rest are set by jail/fine/pardon in later phases.
 */
public enum Resolution {
    UNRESOLVED,
    SERVED,
    FINED,
    PARDONED,
    ESCAPED,
    EXPIRED;

    public static Resolution parse(String name) {
        try {
            return valueOf(name);
        } catch (IllegalArgumentException e) {
            return UNRESOLVED;
        }
    }
}
