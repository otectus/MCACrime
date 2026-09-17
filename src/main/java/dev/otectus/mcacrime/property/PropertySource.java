package dev.otectus.mcacrime.property;

import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;

/**
 * Where a {@link PropertyPolicy} came from, which decides who may overwrite it.
 *
 * <p>A generated policy is re-derived every time the auto-protection sweep runs, so it carries a
 * deterministic id and is replaced in place. A manual one is a decision an operator made and is never
 * overwritten by the sweep — the single rule that keeps "protect generated property" from quietly
 * reverting somebody's hand-made exception the next time the server starts.
 */
public enum PropertySource {

    /** An operator ran {@code /crime property protect}. */
    MANUAL("manual"),

    /** A datapack declared it. */
    DATAPACK("datapack"),

    /** MCA: Crime derived it from a facility or a recognised building type. */
    GENERATED("generated");

    private final String id;

    PropertySource(String id) {
        this.id = id;
    }

    public String id() {
        return id;
    }

    /** Whether the automatic sweep may replace a policy from this source. */
    public boolean regenerable() {
        return this == GENERATED;
    }

    public static Optional<PropertySource> parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        String needle = raw.trim().toLowerCase(Locale.ROOT);
        return Arrays.stream(values())
                .filter(source -> source.id.equals(needle)
                        || source.name().toLowerCase(Locale.ROOT).equals(needle))
                .findFirst();
    }
}
