package dev.otectus.mcacrime.ai;

/**
 * The states one reacting villager moves through (spec §11.2).
 *
 * <p>{@link #CALM} is not a state the controller runs in — it is the absence of a controller. That
 * distinction is the whole performance design: MCA's own AI owns a calm villager completely, and this
 * mod only takes the wheel for the seconds a villager is actually reacting to something. A world of
 * three hundred villagers with two muggings in progress runs two controllers, not three hundred.
 */
public enum VictimReactionState {

    /** No controller. MCA's AI owns the villager entirely. */
    CALM("calm", false, false),
    /** Facing the actor, deciding: comply, resist, or run. */
    THREATENED("threatened", true, false),
    /** Surrendering or handing something over; holds still through the transfer. */
    COMPLYING("complying", true, false),
    /** Pushing back, moving away, or breaking the actor's channel. */
    RESISTING("resisting", true, true),
    /** Running to a scored safe destination. */
    FLEEING("fleeing", true, true),
    /** Moving toward a guard or allied adult with a pending report. */
    SEEKING_HELP("seeking_help", true, true),
    /** At the responder, delivering the report. */
    REPORTING("reporting", true, false),
    /** Home or in a safe building, avoiding the offender. */
    HIDING("hiding", true, true),
    /** Back on normal behaviour, but refusing or altering interaction with the offender. */
    RECOVERING("recovering", false, false),
    /** Held. Incompatible movement suppressed; captive reactions exposed instead. */
    CAPTIVE("captive", true, false);

    private final String lower;
    private final boolean ownsBehaviour;
    private final boolean ownsNavigation;

    VictimReactionState(String lower, boolean ownsBehaviour, boolean ownsNavigation) {
        this.lower = lower;
        this.ownsBehaviour = ownsBehaviour;
        this.ownsNavigation = ownsNavigation;
    }

    public String lower() {
        return lower;
    }

    /**
     * Whether MCA: Crime is driving the villager in this state. False for {@link #CALM} and
     * {@link #RECOVERING}: recovery is a memory window, not a behaviour override, so the villager
     * goes back to farming and trading while still refusing the offender.
     */
    public boolean ownsBehaviour() {
        return ownsBehaviour;
    }

    /**
     * Whether this state issues navigation. Only these states may call {@code moveTo}; the rest leave
     * pathing to MCA, because two systems steering one villager is what produces jitter.
     */
    public boolean ownsNavigation() {
        return ownsNavigation;
    }

    /** Whether the villager is actively trying to get a report to somebody. */
    public boolean carryingReport() {
        return this == SEEKING_HELP || this == REPORTING;
    }

    public String labelKey() {
        return "mcacrime.reaction." + lower;
    }
}
