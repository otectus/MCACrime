package dev.otectus.mcacrime.action;

/**
 * How long an action takes, as a category rather than a tick count (spec §8.4). A player is told
 * "long" and not "137 ticks" on purpose: the exact channel length is config- and restraint-dependent,
 * and publishing it invites frame-counting against the interruption rules.
 */
public enum ActionDuration {
    /** Resolves in the same tick it starts. */
    INSTANT("instant"),
    /** Roughly a second or two. */
    QUICK("quick"),
    /** A few seconds — long enough that a witness can arrive. */
    SHORT("short"),
    /** Long enough that being interrupted is the expected outcome in a populated village. */
    LONG("long");

    private final String lower;

    ActionDuration(String lower) {
        this.lower = lower;
    }

    public String lower() {
        return lower;
    }

    public String labelKey() {
        return "gui.mcacrime.duration." + lower;
    }

    /** Buckets a concrete channel length so a handler can derive its marker from live config. */
    public static ActionDuration ofTicks(int ticks) {
        if (ticks <= 0) return INSTANT;
        if (ticks <= 20) return QUICK;
        if (ticks <= 100) return SHORT;
        return LONG;
    }
}
