package dev.otectus.mcacrime.ai;

import net.minecraft.core.BlockPos;

import java.util.Comparator;
import java.util.List;

/**
 * Chooses where a frightened villager actually goes (spec §11.4).
 *
 * <p>The behaviour this replaces was "path to a point eight blocks directly away from the player",
 * which is wrong in the two situations that matter most: it walks the villager into a wall when the
 * offender is standing in a doorway, and it walks them <em>away</em> from the guard who could help.
 * Scoring candidates fixes both, because a guard four blocks behind the offender can still outscore
 * empty ground in the opposite direction.
 *
 * <p>The scoring half is deliberately free of Minecraft types so the rule can be tested directly. The
 * live sampler in {@link CrimeReactionService} builds {@link Candidate}s and hands them here.
 */
public final class SafeDestinationSelector {

    /** What kind of place a candidate is, in descending order of how good a refuge it is. */
    public enum Kind {
        /** A guard or other law responder. */
        RESPONDER(30.0),
        /** A public building or the village centre, with people in it. */
        PUBLIC_BUILDING(20.0),
        /** Home, or their own bed. */
        HOME(16.0),
        /** An allied adult or family member. */
        ALLY(12.0),
        /** Open ground away from the threat. The fallback, and never preferred to the others. */
        OPEN_GROUND(0.0);

        private final double bonus;

        Kind(double bonus) {
            this.bonus = bonus;
        }

        public double bonus() {
            return bonus;
        }
    }

    /**
     * One place the villager could go.
     *
     * @param position         where it is
     * @param kind             what it is
     * @param distanceFromThreat blocks between the candidate and the offender
     * @param pathCost         blocks the villager must travel to reach it
     * @param reachable        whether navigation actually produced a path
     * @param dangerNearby     0..1, how much other trouble is at the destination
     */
    public record Candidate(BlockPos position, Kind kind, double distanceFromThreat,
                            double pathCost, boolean reachable, double dangerNearby) {
    }

    /** Distance past which extra separation from the threat stops being worth anything. */
    private static final double DISTANCE_CAP = 32.0;

    private SafeDestinationSelector() {
    }

    /**
     * Scores one candidate. Higher is better.
     *
     * <p>Distance from the threat is capped rather than linear: past about thirty blocks the offender
     * is simply not a factor any more, and without the cap a villager would sprint across the whole
     * map rather than step into the guard house twenty blocks away.
     */
    public static double score(Candidate candidate) {
        if (candidate == null || !candidate.reachable()) {
            return Double.NEGATIVE_INFINITY;
        }
        double separation = Math.min(candidate.distanceFromThreat(), DISTANCE_CAP);
        return separation
                + candidate.kind().bonus()
                - candidate.pathCost() * 0.5
                - Math.max(0.0, candidate.dangerNearby()) * 25.0;
    }

    /**
     * The best candidate, or empty when none is reachable.
     *
     * <p>Ties break on position rather than list order, so the same village layout always produces the
     * same choice. A villager that picks a different door each time the same mugging happens reads as
     * a bug even when both doors are equally safe.
     */
    public static java.util.Optional<Candidate> best(List<Candidate> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return java.util.Optional.empty();
        }
        return candidates.stream()
                .filter(candidate -> candidate != null && candidate.reachable())
                .max(Comparator.<Candidate>comparingDouble(SafeDestinationSelector::score)
                        .thenComparingLong(candidate -> candidate.position().asLong()));
    }
}
