package dev.otectus.mcacrime.dialogue;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The normalised facts a dialogue variant is selected against (spec §16.2).
 *
 * <p>Everything is a string, and that is the design rather than laziness. A dialogue pack must be able
 * to say "this line is for a bold villager" without the pack author knowing that boldness is a float
 * derived from a UUID hash and an MCA trait lookup that may not have resolved. Normalising to
 * {@code personality=bold} at the one place that can see all of it means a pack never depends on an
 * MCA Java enum name, which §16.2 requires outright.
 *
 * <p>Facts are additive and unknown keys are simply absent. A variant that asks about a fact this
 * encounter did not produce does not match — it never throws, and it never matches by accident.
 */
public final class DialogueContext {

    private final Map<String, String> facts;
    private final long variantSeed;

    private DialogueContext(Map<String, String> facts, long variantSeed) {
        this.facts = Map.copyOf(facts);
        this.variantSeed = variantSeed;
    }

    public static Builder builder(long variantSeed) {
        return new Builder(variantSeed);
    }

    public Map<String, String> facts() {
        return facts;
    }

    /**
     * The seed that picks which line of a matched variant is spoken. Derived from the encounter, so
     * reopening the same conversation says the same thing — §16.1 requires variants to be
     * deterministic for an encounter, precisely so a player cannot reroll until a line they prefer
     * comes up.
     */
    public long variantSeed() {
        return variantSeed;
    }

    /** Whether one condition entry is satisfied. {@code a|b} means either. */
    boolean satisfies(String key, String expected) {
        String actual = facts.get(key);
        if (actual == null) {
            return false;
        }
        if (expected.indexOf('|') < 0) {
            return actual.equals(normalise(expected));
        }
        for (String option : expected.split("\\|")) {
            if (actual.equals(normalise(option))) {
                return true;
            }
        }
        return false;
    }

    /**
     * Strips the tag decoration the spec's example JSON uses ({@code #mcacrime:bold}) down to the bare
     * value ({@code bold}). Packs may write either form; both mean the same thing, and accepting only
     * one of them would make the specification's own example fail to load.
     */
    static String normalise(String raw) {
        if (raw == null) {
            return "";
        }
        String value = raw.trim();
        if (value.startsWith("#")) {
            value = value.substring(1);
        }
        int colon = value.indexOf(':');
        if (colon >= 0) {
            value = value.substring(colon + 1);
        }
        int slash = value.lastIndexOf('/');
        if (slash >= 0) {
            value = value.substring(slash + 1);
        }
        return value.toLowerCase(java.util.Locale.ROOT);
    }

    public static final class Builder {
        private final Map<String, String> facts = new LinkedHashMap<>();
        private final long variantSeed;

        private Builder(long variantSeed) {
            this.variantSeed = variantSeed;
        }

        public Builder put(String key, String value) {
            if (key != null && value != null && !value.isBlank()) {
                facts.put(key, normalise(value));
            }
            return this;
        }

        public Builder put(String key, boolean value) {
            return put(key, Boolean.toString(value));
        }

        /** Buckets a 0..1 factor into {@code low}/{@code mid}/{@code high} for pack authors. */
        public Builder putBand(String key, float value) {
            return put(key, value < 0.34F ? "low" : value < 0.67F ? "mid" : "high");
        }

        public DialogueContext build() {
            return new DialogueContext(facts, variantSeed);
        }
    }
}
