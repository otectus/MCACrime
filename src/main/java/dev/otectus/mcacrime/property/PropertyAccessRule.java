package dev.otectus.mcacrime.property;

import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;

/**
 * What a {@link PropertyPolicy} permits, in the order an operator thinks about it.
 *
 * <p>The rule answers one question only — who may take from this — and never how much a theft costs.
 * Anything about consequences belongs to the crime type, which is why a policy carries no karma, no
 * heat and no fine: a village that guards its granary more closely than its scrap pile still charges
 * the same crime for taking from either.
 */
public enum PropertyAccessRule {

    /** Anybody may take. A public ration chest, a free-to-take pile. */
    PUBLIC("public"),

    /** Members of the owning settlement may take; outsiders may not. */
    RESIDENTS("residents"),

    /** Only a villager working for the settlement may take — a stores chest for a task. */
    WORKERS("workers"),

    /** Only the named owner may take. */
    OWNER_ONLY("owner_only"),

    /**
     * Nobody may take, including the owner's own villagers.
     *
     * <p>What evidence storage and a cell's supply container get. It is also the only rule under which
     * <em>opening</em> the container is itself refused, and even then opening is never a theft charge:
     * §10.1 is explicit that inspecting a container is not a completed theft.
     */
    FORBIDDEN("forbidden");

    private final String id;

    PropertyAccessRule(String id) {
        this.id = id;
    }

    public String id() {
        return id;
    }

    public static Optional<PropertyAccessRule> parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        String needle = raw.trim().toLowerCase(Locale.ROOT);
        return Arrays.stream(values())
                .filter(rule -> rule.id.equals(needle) || rule.name().toLowerCase(Locale.ROOT).equals(needle))
                .findFirst();
    }

    /** The ids an operator may type, for a command's failure message. */
    public static String names() {
        StringBuilder out = new StringBuilder();
        for (PropertyAccessRule rule : values()) {
            if (out.length() > 0) {
                out.append(", ");
            }
            out.append(rule.id);
        }
        return out.toString();
    }
}
