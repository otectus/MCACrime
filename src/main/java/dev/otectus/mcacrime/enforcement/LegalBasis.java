package dev.otectus.mcacrime.enforcement;

/**
 * Why force against somebody is lawful — the single named reason behind a Legal Target (0.5.1).
 *
 * <p>The ordering is not alphabetical and not arbitrary: it is exactly the precedence
 * {@link LegalTarget#primaryReasonKey} has always applied when choosing which of several
 * simultaneously-true reasons to show a player. Resisting comes first because it is the most recent
 * thing the player actually did; being told "you are Wanted" after refusing a guard to their face
 * explains the wrong half of the encounter.
 *
 * <p>Declared here rather than derived per caller so the guard message, the client indicator and the
 * bounty eligibility test can never name three different reasons for one situation.
 */
public enum LegalBasis {

    RESISTING_ARREST("mcacrime.msg.guardaggro.resisting"),
    HOLDING_CAPTIVE("mcacrime.msg.guardaggro.captor"),
    WANTED("mcacrime.msg.guardaggro.wanted"),
    ESCAPED_PRISONER("mcacrime.msg.guardaggro.escaped"),
    RED_BAND("mcacrime.msg.guardaggro.red"),
    NONE("mcacrime.msg.guardaggro.generic");

    private final String reasonKey;

    LegalBasis(String reasonKey) {
        this.reasonKey = reasonKey;
    }

    /** The lang key explaining this basis to a player. */
    public String reasonKey() {
        return reasonKey;
    }
}
