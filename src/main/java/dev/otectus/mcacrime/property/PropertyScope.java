package dev.otectus.mcacrime.property;

import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;

/**
 * How much of the world one {@link PropertyPolicy} covers.
 *
 * <p>Deliberately only two. A container policy names one block and is answered by a map lookup, which
 * is what makes it usable on the storage-sourcing hot path; a building policy names a settlement
 * building and has to ask the settlement mod which building a position is in, which is not. Keeping
 * them apart in the type is what stops the second from being consulted where only the first can
 * afford to be.
 */
public enum PropertyScope {

    /** One block: a chest, a barrel, a shulker box. */
    CONTAINER("container"),

    /** A recognised settlement building, by reference rather than by bounds. */
    BUILDING("building");

    private final String id;

    PropertyScope(String id) {
        this.id = id;
    }

    public String id() {
        return id;
    }

    public static Optional<PropertyScope> parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        String needle = raw.trim().toLowerCase(Locale.ROOT);
        return Arrays.stream(values())
                .filter(scope -> scope.id.equals(needle) || scope.name().toLowerCase(Locale.ROOT).equals(needle))
                .findFirst();
    }
}
