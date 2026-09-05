package dev.otectus.mcacrime.job;

import dev.otectus.mcacrime.McaCrimeConfig;

import java.util.Optional;
import java.util.UUID;
import java.util.function.DoubleSupplier;

/**
 * Who becomes a criminal, decided as a pure function (0.5.1, spec §"Assignment and spawning").
 *
 * <p>Nothing here touches an entity, a level or the world data. The sweep gathers the facts, this
 * decides, and the service writes — so the rules that matter (children are never assigned, a fence
 * needs a settlement behind it, a village gets one criminal every few days at most) are testable
 * without a server, which is the only way a probability-driven rule can be tested at all.
 *
 * <p>The randomness arrives as a {@link DoubleSupplier} rather than a {@code RandomSource} so a test
 * can feed it an exact sequence and so this class stays free of Minecraft entirely.
 */
public final class CriminalJobAssigner {

    private CriminalJobAssigner() {
    }

    /**
     * Everything the decision needs about one villager, and nothing else. No entity reference: by the
     * time the answer is written the entity may have unloaded, and spec §"Criminal-job representation"
     * forbids persisting one anyway.
     *
     * @param lastAssignDayForVillage the day this villager's village last produced a criminal, or
     *                                {@link Long#MIN_VALUE} when it never has. A sentinel rather than
     *                                zero, because day zero is a real day on a fresh world and would
     *                                otherwise put every village on cooldown for its first three days.
     */
    public record Candidate(UUID id, boolean adult, boolean guardOrArcher, boolean alreadyCriminal,
                            boolean jailed, boolean protectedNpc, boolean hasVillage, int villagePopulation,
                            long lastAssignDayForVillage) {
    }

    /** The {@code criminalJobs} config block, snapshotted so the roll is reproducible. */
    public record AssignmentPolicy(boolean enableThieves, boolean enableFences, double villageThiefChance,
                                   double villageFenceChance, double wildThiefChance,
                                   int minVillagePopulationForFence, int criminalAssignmentCooldownDays) {

        public static AssignmentPolicy fromConfig() {
            McaCrimeConfig.Common c = McaCrimeConfig.COMMON;
            return new AssignmentPolicy(
                    c.enableThieves.get(),
                    c.enableFences.get(),
                    c.villageThiefChance.get(),
                    c.villageFenceChance.get(),
                    c.wildThiefChance.get(),
                    c.minVillagePopulationForFence.get(),
                    c.criminalAssignmentCooldownDays.get());
        }
    }

    /**
     * Rolls for one candidate. Empty means "stays honest", which is what almost every call returns.
     *
     * <p>Order matters twice. The exclusions come first and cost nothing, so a village of children and
     * guards consumes no randomness at all. Fence is rolled before thief because a fence has a
     * precondition a thief does not — it needs a settlement of a given size — and a village that
     * qualifies for one should not have its single assignment spent on a thief first.
     */
    public static Optional<CriminalJob> roll(Candidate candidate, AssignmentPolicy policy, long today,
                                             DoubleSupplier random) {
        if (candidate == null || policy == null || random == null) {
            return Optional.empty();
        }
        if (!candidate.adult() || candidate.guardOrArcher() || candidate.alreadyCriminal()
                || candidate.jailed() || candidate.protectedNpc()) {
            return Optional.empty();
        }
        if (!candidate.hasVillage()) {
            // The wild path: no village, so no cooldown to respect and no fence to become. Spec is
            // explicit that a lone fence in the wilderness is not a spawn path worth having.
            return policy.enableThieves() && roll(random, policy.wildThiefChance())
                    ? Optional.of(CriminalJob.THIEF)
                    : Optional.empty();
        }
        if (onCooldown(candidate.lastAssignDayForVillage(), today, policy.criminalAssignmentCooldownDays())) {
            return Optional.empty();
        }
        if (policy.enableFences() && candidate.villagePopulation() >= policy.minVillagePopulationForFence()
                && roll(random, policy.villageFenceChance())) {
            return Optional.of(CriminalJob.FENCE);
        }
        if (policy.enableThieves() && roll(random, policy.villageThiefChance())) {
            return Optional.of(CriminalJob.THIEF);
        }
        return Optional.empty();
    }

    /**
     * Whether this village has produced a criminal too recently.
     *
     * <p>A clock that has gone backwards (a restored backup, a {@code /time set}) reads as "not on
     * cooldown" rather than as a cooldown lasting until the old day comes round again.
     */
    private static boolean onCooldown(long lastAssignDay, long today, int cooldownDays) {
        if (lastAssignDay == Long.MIN_VALUE || cooldownDays <= 0 || today < lastAssignDay) {
            return false;
        }
        return today - lastAssignDay < cooldownDays;
    }

    /** A chance of 0 never fires and a chance of 1 always does, whatever the supplier returns. */
    private static boolean roll(DoubleSupplier random, double chance) {
        if (chance <= 0.0D) {
            return false;
        }
        return chance >= 1.0D || random.getAsDouble() < chance;
    }
}
