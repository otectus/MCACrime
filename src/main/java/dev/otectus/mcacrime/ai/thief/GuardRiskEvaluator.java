package dev.otectus.mcacrime.ai.thief;

import java.util.List;

/**
 * Turns a list of nearby guard sightings into one number (spec §"Thieves must avoid guards").
 *
 * <p>Pure, and separate from the scan that produces the sightings, because the scoring is the part
 * worth testing and the scan is the part that needs a world. Sight matters more than distance: a
 * guard eight blocks away round a corner is a smaller problem than one fifteen blocks away looking
 * straight at you, and a thief who cannot tell the difference never uses an alley.
 */
public final class GuardRiskEvaluator {

    /** Extra risk per additional guard beyond the nearest, and the ceiling on that term. */
    private static final double CROWD_STEP = 0.10D;
    private static final double CROWD_CAP = 0.30D;
    /** Weight of the distance term with and without line of sight. */
    private static final double SEEN_WEIGHT = 1.0D;
    private static final double UNSEEN_WEIGHT = 0.35D;
    /** Flat penalty for a guard already inside the hard-abort radius. */
    private static final double CLOSE_SEEN = 1.0D;
    private static final double CLOSE_UNSEEN = 0.50D;

    /** One guard as the thief perceives it: how far, and whether it can see the thief. */
    public record GuardSighting(double distance, boolean lineOfSight) {
    }

    private GuardRiskEvaluator() {
    }

    /**
     * @param sightings       guards within the avoidance radius; anything further is ignored
     * @param avoidRadius     distance at which a guard stops mattering at all
     * @param hardAbortRadius distance inside which a guard is a problem whatever else is true
     */
    public static GuardRisk evaluate(List<GuardSighting> sightings, double avoidRadius, double hardAbortRadius) {
        if (sightings == null || sightings.isEmpty() || avoidRadius <= 0.0D) {
            return GuardRisk.none();
        }
        int count = 0;
        boolean seen = false;
        GuardSighting nearest = null;
        for (GuardSighting sighting : sightings) {
            if (sighting == null || sighting.distance() > avoidRadius) {
                continue;
            }
            count++;
            seen |= sighting.lineOfSight();
            if (nearest == null || sighting.distance() < nearest.distance()) {
                nearest = sighting;
            }
        }
        if (nearest == null) {
            return GuardRisk.none();
        }

        double proximity = Math.max(0.0D, Math.min(1.0D, (avoidRadius - nearest.distance()) / avoidRadius));
        double score = proximity * (nearest.lineOfSight() ? SEEN_WEIGHT : UNSEEN_WEIGHT);
        if (nearest.distance() <= hardAbortRadius) {
            score += nearest.lineOfSight() ? CLOSE_SEEN : CLOSE_UNSEEN;
        }
        score += Math.min(CROWD_CAP, CROWD_STEP * (count - 1));
        return new GuardRisk(count, seen, nearest.distance(), score);
    }
}
