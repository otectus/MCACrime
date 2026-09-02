package dev.otectus.mcacrime.action;

/**
 * A visible prerequisite for an action, shown as a small marker on its row (spec §8.4). These are the
 * requirements a player can reasonably be told about — holding a restraint, having a free hand, the
 * target being vulnerable. They are never the hidden ones: compliance rolls, purse balances, witness
 * confidence and guard-response probability stay server-side.
 *
 * <p>A requirement marker is advisory. The server still re-evaluates every requirement on click, so a
 * client that never drew these markers cannot gain anything by omitting them.
 */
public enum ActionRequirement {
    /** A rope, cuffs, or locked cuffs somewhere in the actor's inventory. */
    RESTRAINT("restraint"),
    /** A key or lockpick for a locked restraint. */
    KEY("key"),
    /** An empty main hand. */
    FREE_HAND("free_hand"),
    /** The target meets a vulnerability condition (low health, asleep, or surrendered). */
    TARGET_VULNERABLE("target_vulnerable"),
    /** The actor owns an active custody record for this exact target. */
    OWN_CAPTIVE("own_captive"),
    /** The actor holds a server-granted enforcement role. */
    AUTHORITY("authority"),
    /** The actor must be able to pay in emeralds. */
    FUNDS("funds"),
    /** The actor has at least one unresolved case. */
    OPEN_CASE("open_case");

    private final String lower;

    ActionRequirement(String lower) {
        this.lower = lower;
    }

    public String lower() {
        return lower;
    }

    public String labelKey() {
        return "gui.mcacrime.requirement." + lower;
    }
}
