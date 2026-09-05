package dev.otectus.mcacrime.ai.thief;

/**
 * How exposed a thief is to the law right now, as spec §"Thieves must avoid guards" defines it.
 *
 * <p>Cached rather than recomputed: the scan behind it is an AABB query, and a thief that asked the
 * question every tick would cost more than the mugging is worth.
 */
public record GuardRisk(int nearbyCount, boolean hasLineOfSight, double nearestDistance, double riskScore) {

    /** No guard within the avoidance radius. */
    public static GuardRisk none() {
        return new GuardRisk(0, false, Double.MAX_VALUE, 0.0D);
    }

    /** Whether this is bad enough to refuse a target or break off an approach. */
    public boolean exceeds(double threshold) {
        return riskScore >= threshold;
    }
}
