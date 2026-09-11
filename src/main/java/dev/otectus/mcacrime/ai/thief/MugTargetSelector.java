package dev.otectus.mcacrime.ai.thief;

import dev.otectus.mcacrime.mug.npc.MugProtectionRules;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Which player, if any, a thief should try to rob (spec §"Victim selection").
 *
 * <p>Pure and total: the caller does the world queries and hands over facts, so the eligibility
 * matrix and the opportunity score are both testable with no server. The eligibility half is a hard
 * gate — an armed or creative player is never a candidate, however attractive the rest of the row
 * looks — and the score only ever orders what survived it.
 *
 * <p>{@code wealthHint} is a hint on purpose. Spec §"Victim selection" forbids walking a player's
 * inventory every AI tick to estimate it, so the caller samples something cheap once per scan.
 */
public final class MugTargetSelector {

    /** Weight of being close, of being alone, and of looking worth robbing. */
    private static final double DISTANCE_WEIGHT = 2.0D;
    private static final double ISOLATION_BONUS = 1.0D;
    /** Currency at which the wealth term saturates; above this, richer stops mattering. */
    private static final double WEALTH_SATURATION = 32.0D;
    private static final double CROWD_PENALTY = 0.35D;
    private static final double FAILURE_PENALTY = 0.50D;

    /**
     * One player as the thief perceives them. Everything here is cheap to obtain from a bounded
     * scan; nothing requires reading an inventory.
     */
    public record VictimCandidate(UUID id, double distance, boolean armed, boolean creativeOrSpectator,
                                  boolean invulnerable, boolean alreadyTargeted, boolean immune,
                                  boolean lineOfSight, int nearbyPlayers, long wealthHint,
                                  int recentFailures, long protectedUntilTick, long pairCooldownUntilTick,
                                  int muggingsToday, int dailyCap, long now) {

        /**
         * A candidate with no victim-scoped protection at all (0.7.0).
         *
         * <p>The five protection facts arrived after the other eleven, and the callers that predate
         * them - the debug command, and every test written against the selection matrix - are asking a
         * question that does not involve them. Defaulting to "unprotected" keeps those call sites
         * honest rather than making them pass five zeroes each.
         */
        public VictimCandidate(UUID id, double distance, boolean armed, boolean creativeOrSpectator,
                               boolean invulnerable, boolean alreadyTargeted, boolean immune,
                               boolean lineOfSight, int nearbyPlayers, long wealthHint, int recentFailures) {
            this(id, distance, armed, creativeOrSpectator, invulnerable, alreadyTargeted, immune,
                    lineOfSight, nearbyPlayers, wealthHint, recentFailures, 0L, 0L, 0, 0, 0L);
        }
    }

    /** A candidate that passed the gate, with the opportunity score that ordered it. */
    public record Scored(UUID id, double score) {
    }

    private MugTargetSelector() {
    }

    /**
     * Whether this candidate may be robbed at all, before any question of whether it is worth it.
     *
     * <p>The three victim-scoped rules added in 0.7.0 sit here with the rest of the hard gate rather
     * than in the score, because that is exactly what was wrong with NPC mugging: a protection that
     * only lowered a number would still be outvoted by a rich player standing close enough.
     */
    public static boolean eligible(VictimCandidate candidate, ThiefPolicy policy) {
        return candidate != null
                && MugProtectionRules.eligible(candidate.protectedUntilTick(),
                        candidate.pairCooldownUntilTick(), candidate.muggingsToday(), candidate.dailyCap(),
                        candidate.now())
                && !candidate.armed()
                && !candidate.creativeOrSpectator()
                && !candidate.invulnerable()
                && !candidate.alreadyTargeted()
                && !candidate.immune()
                && candidate.lineOfSight()
                && candidate.distance() <= policy.targetSearchRadius();
    }

    public static double score(VictimCandidate candidate, GuardRisk risk, ThiefPolicy policy) {
        double radius = Math.max(1.0D, policy.targetSearchRadius());
        double distanceScore = DISTANCE_WEIGHT * (1.0D - Math.min(1.0D, candidate.distance() / radius));
        double isolationScore = candidate.nearbyPlayers() <= 0 ? ISOLATION_BONUS : 0.0D;
        double wealthScore = Math.min(1.0D, Math.max(0L, candidate.wealthHint()) / WEALTH_SATURATION);
        double guardExposure = risk == null ? 0.0D : risk.riskScore();
        double crowdExposure = CROWD_PENALTY * Math.max(0, candidate.nearbyPlayers());
        double failurePenalty = FAILURE_PENALTY * Math.max(0, candidate.recentFailures());
        return distanceScore + isolationScore + wealthScore - guardExposure - crowdExposure - failurePenalty;
    }

    /**
     * The best candidate, or empty when there is none worth the risk.
     *
     * <p>Guard risk is checked once for the whole selection rather than per candidate: it describes
     * where the thief is standing, not who they are looking at, and spec §"Thieves must avoid guards"
     * wants the target refused outright rather than merely scored down when it is high.
     */
    public static Optional<Scored> select(List<VictimCandidate> candidates, GuardRisk risk, ThiefPolicy policy) {
        if (candidates == null || candidates.isEmpty()
                || (risk != null && risk.exceeds(policy.guardRiskAbortThreshold()))) {
            return Optional.empty();
        }
        return candidates.stream()
                .filter(candidate -> eligible(candidate, policy))
                .map(candidate -> new Scored(candidate.id(), score(candidate, risk, policy)))
                // Ties broken by id so two thieves scanning the same crowd on the same tick do not
                // depend on iteration order for who they pick.
                .max(Comparator.comparingDouble(Scored::score)
                        .thenComparing(scored -> scored.id().toString()));
    }
}
