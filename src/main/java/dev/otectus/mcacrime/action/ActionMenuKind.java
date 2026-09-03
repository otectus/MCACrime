package dev.otectus.mcacrime.action;

/**
 * Which menu a server-issued action list is for. The client uses it to pick a screen; the server uses
 * it to pick the action set. It is deliberately part of the packet rather than inferred from the
 * target id, because "the target happens to be me" and "this is the captive panel" are different
 * questions and conflating them would let one screen be opened in place of the other.
 */
public enum ActionMenuKind {
    /** Actions against a villager, opened from MCA's interaction screen or a world interaction. */
    VILLAGER,
    /** Actions available to a player being held, opened while captive. */
    CAPTIVE,
    /** Actions a player can take about their own legal standing, opened from the player card. */
    SELF
}
