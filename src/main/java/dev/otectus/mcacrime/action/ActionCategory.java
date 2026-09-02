package dev.otectus.mcacrime.action;

/**
 * How the action screen groups one action (spec §8.4). The category is presentation only — it never
 * affects validation, and the server re-evaluates availability on click regardless of which group a
 * row was drawn in.
 */
public enum ActionCategory {
    /** Threaten, pickpocket, mug, extort, intimidate. */
    COERCE("coerce"),
    /** Restrain, search a captive, move/secure/release a captive. */
    RESTRAIN("restrain"),
    /** Apologize, offer restitution, report, rescue, lawful arrest, settle a case. */
    RESOLVE("resolve"),
    /** Relationship-, profession-, case- or pack-defined actions. */
    SPECIAL("special");

    private final String lower;

    ActionCategory(String lower) {
        this.lower = lower;
    }

    /** Stable lowercase id used to build the {@code gui.mcacrime.category.*} translation key. */
    public String lower() {
        return lower;
    }

    public String labelKey() {
        return "gui.mcacrime.category." + lower;
    }
}
