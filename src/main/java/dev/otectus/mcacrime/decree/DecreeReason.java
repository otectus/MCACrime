package dev.otectus.mcacrime.decree;

import java.util.Locale;
import java.util.Optional;

/**
 * Why a ruler says a decree was issued (0.7.8): a closed vocabulary, so the reason can be shown in any
 * language and can never carry text another player wrote.
 *
 * <p>A reason is the Crown's statement, never evidence. It creates no case, names no victim, and is
 * shown only to the target, the ruler and the court's chronicle when that is switched on.
 */
public enum DecreeReason {
    TRESPASS,
    DISORDER,
    DEFIANCE,
    TREASON,
    OTHER;

    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** The translation key, {@code mcacrime.decree.reason.<id>}. */
    public String labelKey() {
        return "mcacrime.decree.reason." + id();
    }

    /** English, for the chronicle's server-locale line on a dedicated server. */
    public String fallback() {
        return switch (this) {
            case TRESPASS -> "trespass";
            case DISORDER -> "disorder";
            case DEFIANCE -> "defiance of the Crown";
            case TREASON -> "treason";
            case OTHER -> "the Crown's own reasons";
        };
    }

    public static Optional<DecreeReason> parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(valueOf(raw.trim().toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
