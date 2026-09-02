package dev.otectus.mcacrime.memory;

/**
 * Where one observation stands in the reporting flow (spec §12.3).
 *
 * <p>{@code SUPPRESSED} and {@code EXPIRED} are separate on purpose. Intimidation or a bribe may
 * suppress <em>one</em> pending report; it must never look like the observation stopped existing,
 * because the same villager can be intimidated again by the same offender and the server has to be
 * able to tell "they were leaned on twice" from "they forgot".
 */
public enum ReportState {
    /** Seen, not yet delivered to anybody who can act on it. */
    PENDING("pending"),
    /** Delivered to an authority; a report exists and a case may have been opened. */
    FILED("filed"),
    /** Aged out under the crime's statute without ever reaching an authority. */
    EXPIRED("expired"),
    /** Deliberately held back — intimidated, bribed, or the observer is incapable of reporting. */
    SUPPRESSED("suppressed");

    private final String lower;

    ReportState(String lower) {
        this.lower = lower;
    }

    public String lower() {
        return lower;
    }

    public String labelKey() {
        return "mcacrime.report.state." + lower;
    }

    public static ReportState byName(String name) {
        for (ReportState state : values()) {
            if (state.name().equals(name) || state.lower.equals(name)) {
                return state;
            }
        }
        return PENDING;
    }
}
