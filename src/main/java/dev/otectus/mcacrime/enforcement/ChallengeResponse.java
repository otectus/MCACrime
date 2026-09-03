package dev.otectus.mcacrime.enforcement;

/**
 * How a challenged player may answer a guard (spec §13.2).
 *
 * <p>Refusal is a real option rather than an absence of one, and no answer resolves to it. That is
 * the rule that makes the challenge window meaningful: waiting the guard out is a choice with the
 * same consequence as saying no, so a player cannot avoid the encounter by not touching the screen.
 */
public enum ChallengeResponse {
    /** Give up: hand over to the guard on the surrender terms. */
    SURRENDER("surrender"),
    /** Settle every finable charge on the spot. */
    PAY_FINE("pay_fine"),
    /** Ask what the charges actually are. Costs the player nothing and does not close the window. */
    ASK_CHARGES("ask_charges"),
    /** Refuse. The guard is then entitled to use force. */
    REFUSE("refuse");

    private final String lower;

    ChallengeResponse(String lower) {
        this.lower = lower;
    }

    public String lower() {
        return lower;
    }

    public String labelKey() {
        return "gui.mcacrime.challenge." + lower;
    }

    /** Whether answering this way ends the encounter. Asking for the charges deliberately does not. */
    public boolean closesEncounter() {
        return this != ASK_CHARGES;
    }

    public static ChallengeResponse byOrdinal(int ordinal) {
        ChallengeResponse[] values = values();
        return ordinal >= 0 && ordinal < values.length ? values[ordinal] : REFUSE;
    }
}
