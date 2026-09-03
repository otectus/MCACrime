package dev.otectus.mcacrime.ransom;

/** Lifecycle of a ransom demand (spec §8.5). Only {@link #OPEN} is non-terminal. */
public enum RansomStatus {
    OPEN,
    PAID,
    FAILED_VICTIM_GONE,
    FAILED_JAILED,
    FAILED_RESCUED,
    EXPIRED,
    CANCELLED;

    public boolean isTerminal() {
        return this != OPEN;
    }

    public static RansomStatus parse(String name) {
        try {
            return valueOf(name);
        } catch (IllegalArgumentException e) {
            return CANCELLED;
        }
    }
}
