package dev.otectus.mcacrime.ai;

/**
 * What a threatened villager does about it (0.5.1, spec §"threat compliance").
 *
 * <p>Pure, and deliberately the only place the ordering lives. The rule that matters most is the one
 * that is easiest to get wrong: compliance is not a reaction to a weapon, it is a reaction to being
 * <em>robbed</em>. A villager freezes because somebody has an open coercive action against them right
 * now, never because an armed player walked past. {@code coerciveSessionActive} is that fact, resolved
 * by the caller from {@code ActionSessionManager.activeCoerciveAgainst(villager)}, and no amount of
 * drawn steel produces {@link Decision#COMPLY} without it.
 *
 * <p>Order, first match wins:
 * <ol>
 *   <li>the villager is armed and armed villagers are allowed to resist — {@link Decision#RESIST},
 *       whether or not a session is open (a guard is not mugged, a guard is attacked);</li>
 *   <li>a coercive session is open and freezing is enabled — {@link Decision#COMPLY};</li>
 *   <li>the villager is brave enough — {@link Decision#RESIST};</li>
 *   <li>help is within reach and worth fetching — {@link Decision#SEEK_HELP};</li>
 *   <li>{@link Decision#FLEE}.</li>
 * </ol>
 */
public final class ThreatComplianceDecider {

    /** The four answers. Each maps 1:1 onto a {@link VictimReactionState} the service transitions to. */
    public enum Decision {
        RESIST,
        COMPLY,
        SEEK_HELP,
        FLEE
    }

    private ThreatComplianceDecider() {
    }

    /**
     * @param armed                 the verdict from {@link ArmedResolver}
     * @param coerciveSessionActive whether a coercive action session names this villager as its target
     * @param resistanceScore       the villager's bravery term, as {@code ReactionFactors} computes it
     * @param helpSeekingScore      the villager's fetch-a-guard term, same source
     * @param helpNearby            whether there is actually a responder to fetch
     * @param armedVillagersCanResist config: off makes even a guard comply with a mugging
     * @param freezeComplyingVictims  config: off means a mugged civilian runs instead of holding still
     */
    public static Decision decide(ArmedResolver.ArmedStatus armed, boolean coerciveSessionActive,
                                  double resistanceScore, double helpSeekingScore, boolean helpNearby,
                                  boolean armedVillagersCanResist, boolean freezeComplyingVictims,
                                  double resistThreshold, double helpThreshold) {
        if (armed != null && armed.armed() && armedVillagersCanResist) {
            return Decision.RESIST;
        }
        if (coerciveSessionActive && freezeComplyingVictims) {
            return Decision.COMPLY;
        }
        if (resistanceScore >= resistThreshold) {
            return Decision.RESIST;
        }
        if (helpNearby && helpSeekingScore >= helpThreshold) {
            return Decision.SEEK_HELP;
        }
        return Decision.FLEE;
    }
}
