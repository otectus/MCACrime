package dev.otectus.mcacrime.decree;

import java.util.Locale;
import java.util.Optional;

/**
 * Where a royal decree is in its life (0.7.8). Only {@link #OPEN} is enforced; every other state is
 * final and is kept for a retention window so the cooldown and the history can still be read.
 */
public enum DecreeState {
    /** Issued and not yet ended. Enforced while the capital and the target still allow it. */
    OPEN,
    /** Withdrawn by the capital's ruler. */
    WITHDRAWN,
    /** Settled by paying the decree's fine. */
    FINED,
    /** Answered by custody: the term was served, bailed or pardoned. */
    SERVED,
    /** Ran its full duration. */
    EXPIRED,
    /** Ended because it could no longer stand: the target was ennobled, the capital ended, or an operator voided it. */
    VOID;

    public boolean terminal() {
        return this != OPEN;
    }

    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** The translation key for this state, {@code mcacrime.decree.state.<id>}. */
    public String labelKey() {
        return "mcacrime.decree.state." + id();
    }

    public static Optional<DecreeState> parse(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(valueOf(raw.trim().toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
