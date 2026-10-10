package dev.otectus.mcacrime.decree;

import java.util.Locale;
import java.util.Optional;

/** Which door a decree, or the act that ended it, came through (0.7.8). Recorded, never trusted. */
public enum DecreeProvenance {
    /** The decree screen opened from a court officer or guard. */
    MENU,
    /** {@code /crime decree}. */
    COMMAND,
    /** An operator's {@code /crime decree void}. */
    OPERATOR,
    /** The system itself: expiry, a lifecycle check, a sentence ending. */
    SYSTEM;

    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static Optional<DecreeProvenance> parse(String raw) {
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
