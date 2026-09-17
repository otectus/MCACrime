package dev.otectus.mcacrime.property;

import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;

/**
 * Who a {@link PropertyPolicy} says a container or a building belongs to.
 *
 * <p>Four kinds rather than a free-form string because the answer decides who may take from it, and
 * an owner nobody can compare an actor against is the same as no owner at all — which is exactly the
 * state {@link PropertyAccess} answers {@code UNKNOWN} for, and never a charge.
 */
public enum PropertyOwnerKind {

    /** The settlement as a whole. Membership, not identity, is what an actor is matched on. */
    VILLAGE("village"),

    /** One villager, by UUID. */
    VILLAGER("villager"),

    /** One player, by UUID. */
    PLAYER("player"),

    /**
     * An MCA: Crime facility — evidence storage, a cell's supply container.
     *
     * <p>No actor ever matches this owner. That is the point: a facility belongs to the law rather
     * than to a person, so "owner only" on one means nobody at all, and an operator wanting a guard to
     * be able to take from it says so with {@link PropertyAccessRule#WORKERS} instead.
     */
    FACILITY("facility");

    private final String id;

    PropertyOwnerKind(String id) {
        this.id = id;
    }

    /** The stable string form, as it appears in saved data and in {@code /crime property}. */
    public String id() {
        return id;
    }

    /** Resolves an id or a constant name, in any case. Empty for anything else. */
    public static Optional<PropertyOwnerKind> parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        String needle = raw.trim().toLowerCase(Locale.ROOT);
        return Arrays.stream(values())
                .filter(kind -> kind.id.equals(needle) || kind.name().toLowerCase(Locale.ROOT).equals(needle))
                .findFirst();
    }
}
