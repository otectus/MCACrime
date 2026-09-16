package dev.otectus.mcacrime;

import dev.otectus.mcacrime.job.CriminalJob;
import dev.otectus.mcacrime.job.CriminalJobAssigner;
import dev.otectus.mcacrime.job.CriminalJobAssigner.AssignmentPolicy;
import dev.otectus.mcacrime.job.CriminalJobAssigner.Candidate;
import dev.otectus.mcacrime.job.NpcMuggerEligibility;
import dev.otectus.mcacrime.job.NpcMuggerEligibilityReason;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.Random;
import java.util.UUID;
import java.util.function.DoubleSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Who is allowed to become a criminal, and who is never allowed to.
 *
 * <p>The exclusions are the half of this that matters. A probability that is slightly wrong makes a
 * village feel off; a missing exclusion turns a child, a guard or a prisoner into a thief, and the
 * spec lists each one because each is a bug somebody has shipped before. They are asserted here with
 * a rigged supplier that always rolls zero — that is, one that would say yes to <em>any</em> chance —
 * so a failure can only mean the exclusion itself is gone.
 */
class CriminalJobAssignerTest {

    /** Every chance fires: whatever comes back is the exclusion logic's answer, not luck's. */
    private static final DoubleSupplier ALWAYS = () -> 0.0D;
    /** No chance fires. */
    private static final DoubleSupplier NEVER = () -> 0.999999D;

    /**
     * The shared role decision, which replaced this record's old {@code guardOrArcher} flag. The two
     * values a test needs are "no objection" and "this one is law".
     */
    private static final NpcMuggerEligibility.Result OK =
            new NpcMuggerEligibility.Result(true, NpcMuggerEligibilityReason.ELIGIBLE);
    private static final NpcMuggerEligibility.Result LAW =
            new NpcMuggerEligibility.Result(false, NpcMuggerEligibilityReason.RESPONDER);
    /** MCA could not be asked. Not the same as LAW, and must be just as excluding. */
    private static final NpcMuggerEligibility.Result UNKNOWN =
            new NpcMuggerEligibility.Result(false, NpcMuggerEligibilityReason.CLASSIFICATION_UNAVAILABLE);

    private static final AssignmentPolicy POLICY =
            new AssignmentPolicy(true, true, 0.025D, 0.010D, 0.0025D, 5, 3);

    private static Candidate villager() {
        return new Candidate(UUID.randomUUID(), true, OK, false, false, false, true, 12, Long.MIN_VALUE);
    }

    private static Candidate wild() {
        return new Candidate(UUID.randomUUID(), true, OK, false, false, false, false, 0, Long.MIN_VALUE);
    }

    @Test
    void anEligibleVillageAdultCanBecomeCriminal() {
        assertTrue(CriminalJobAssigner.roll(villager(), POLICY, 100L, ALWAYS).isPresent());
    }

    @Test
    void childrenAreNeverAssigned() {
        Candidate child = new Candidate(UUID.randomUUID(), false, OK, false, false, false, true, 12, Long.MIN_VALUE);
        assertTrue(CriminalJobAssigner.roll(child, POLICY, 100L, ALWAYS).isEmpty());
    }

    @Test
    void lawAlreadyCriminalJailedAndProtectedAreNeverAssigned() {
        UUID id = UUID.randomUUID();
        assertTrue(CriminalJobAssigner.roll(
                new Candidate(id, true, LAW, false, false, false, true, 12, Long.MIN_VALUE),
                POLICY, 100L, ALWAYS).isEmpty(), "a guard or archer is law, not a recruit");
        assertTrue(CriminalJobAssigner.roll(
                new Candidate(id, true, OK, true, false, false, true, 12, Long.MIN_VALUE),
                POLICY, 100L, ALWAYS).isEmpty(), "an existing criminal is not re-rolled");
        assertTrue(CriminalJobAssigner.roll(
                new Candidate(id, true, OK, false, true, false, true, 12, Long.MIN_VALUE),
                POLICY, 100L, ALWAYS).isEmpty(), "a prisoner keeps the job they have and gains none");
        assertTrue(CriminalJobAssigner.roll(
                new Candidate(id, true, OK, false, false, true, true, 12, Long.MIN_VALUE),
                POLICY, 100L, ALWAYS).isEmpty(), "a protected NPC is left alone");
    }

    @Test
    void aFenceNeedsAVillageBigEnoughToKeepOne() {
        Candidate hamlet = new Candidate(UUID.randomUUID(), true, OK, false, false, false, true, 4, Long.MIN_VALUE);
        assertEquals(Optional.of(CriminalJob.THIEF), CriminalJobAssigner.roll(hamlet, POLICY, 100L, ALWAYS),
                "below minVillagePopulationForFence the fence roll is skipped entirely");
        Candidate town = new Candidate(UUID.randomUUID(), true, OK, false, false, false, true, 5, Long.MIN_VALUE);
        assertEquals(Optional.of(CriminalJob.FENCE), CriminalJobAssigner.roll(town, POLICY, 100L, ALWAYS));
    }

    @Test
    void theWildPathProducesThievesOnly() {
        assertEquals(Optional.of(CriminalJob.THIEF), CriminalJobAssigner.roll(wild(), POLICY, 100L, ALWAYS),
                "a lone fence in the wilderness is not a spawn path the spec wants");
    }

    @Test
    void theWildChanceIsTheOnlyOneAVillagerlessCandidateRolls() {
        AssignmentPolicy noWild = new AssignmentPolicy(true, true, 1.0D, 1.0D, 0.0D, 5, 3);
        assertTrue(CriminalJobAssigner.roll(wild(), noWild, 100L, ALWAYS).isEmpty(),
                "village chances of 1.0 must not leak into the wild path");
    }

    @Test
    void aVillageOnCooldownProducesNobody() {
        Candidate recent = new Candidate(UUID.randomUUID(), true, OK, false, false, false, true, 12, 98L);
        assertTrue(CriminalJobAssigner.roll(recent, POLICY, 100L, ALWAYS).isEmpty(),
                "two days after the last assignment is inside the three-day cooldown");
        Candidate older = new Candidate(UUID.randomUUID(), true, OK, false, false, false, true, 12, 97L);
        assertTrue(CriminalJobAssigner.roll(older, POLICY, 100L, ALWAYS).isPresent(),
                "three days later the village is eligible again");
    }

    @Test
    void aVillageThatHasNeverAssignedIsNotOnCooldownOnDayZero() {
        Candidate fresh = new Candidate(UUID.randomUUID(), true, OK, false, false, false, true, 12, Long.MIN_VALUE);
        assertTrue(CriminalJobAssigner.roll(fresh, POLICY, 0L, ALWAYS).isPresent(),
                "day zero is a real day; 'never assigned' must not read as 'assigned on day zero'");
    }

    @Test
    void theWildPathIgnoresTheVillageCooldown() {
        Candidate loner = new Candidate(UUID.randomUUID(), true, OK, false, false, false, false, 0, 100L);
        assertTrue(CriminalJobAssigner.roll(loner, POLICY, 100L, ALWAYS).isPresent(),
                "a villager with no village is not on any village's cooldown");
    }

    @Test
    void disabledOccupationsAreNotRolled() {
        AssignmentPolicy off = new AssignmentPolicy(false, false, 1.0D, 1.0D, 1.0D, 1, 0);
        assertTrue(CriminalJobAssigner.roll(villager(), off, 100L, ALWAYS).isEmpty());
        assertTrue(CriminalJobAssigner.roll(wild(), off, 100L, ALWAYS).isEmpty());
    }

    @Test
    void aChanceOfZeroNeverFiresAndAChanceOfOneAlwaysDoes() {
        AssignmentPolicy none = new AssignmentPolicy(true, true, 0.0D, 0.0D, 0.0D, 5, 0);
        assertTrue(CriminalJobAssigner.roll(villager(), none, 100L, ALWAYS).isEmpty());
        AssignmentPolicy certain = new AssignmentPolicy(true, false, 1.0D, 0.0D, 0.0D, 5, 0);
        assertEquals(Optional.of(CriminalJob.THIEF), CriminalJobAssigner.roll(villager(), certain, 100L, NEVER));
    }

    @Test
    void theSameSeedProducesTheSameVillage() {
        // Determinism is what makes a balance complaint reproducible: the same seed, the same
        // candidates and the same policy must always produce the same criminals.
        assertEquals(rollBatch(1234L), rollBatch(1234L));
        assertFalse(rollBatch(1234L).chars().allMatch(c -> c == '-'),
                "a generous policy that produces nobody at all would make the check above vacuous");
    }

    private static String rollBatch(long seed) {
        Random random = new Random(seed);
        AssignmentPolicy generous = new AssignmentPolicy(true, true, 0.5D, 0.2D, 0.1D, 5, 0);
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < 40; i++) {
            out.append(CriminalJobAssigner.roll(villager(), generous, 100L, random::nextDouble)
                    .map(CriminalJob::id).orElse("-"));
        }
        return out.toString();
    }

    // ---------------------------------------------------------------- the role gate (0.7.2)

    /**
     * The exclusion the sweep used to make itself, from a bare guard/archer test. It now arrives as
     * the shared decision, so the sweep and every other mutation path agree by construction.
     */
    @Test
    void aRejectedRoleIsNeverRolledForInAnyPath() {
        Candidate lawInVillage = new Candidate(UUID.randomUUID(), true, LAW, false, false, false,
                true, 12, Long.MIN_VALUE);
        Candidate lawInTheWild = new Candidate(UUID.randomUUID(), true, LAW, false, false, false,
                false, 0, Long.MIN_VALUE);
        assertTrue(CriminalJobAssigner.roll(lawInVillage, POLICY, 100L, ALWAYS).isEmpty());
        assertTrue(CriminalJobAssigner.roll(lawInTheWild, POLICY, 100L, ALWAYS).isEmpty());
    }

    /** An unreadable classification excludes exactly as firmly as a positive one. */
    @Test
    void anUnknownRoleIsNotRecruited() {
        Candidate unclassifiable = new Candidate(UUID.randomUUID(), true, UNKNOWN, false, false, false,
                true, 12, Long.MIN_VALUE);
        assertTrue(CriminalJobAssigner.roll(unclassifiable, POLICY, 100L, ALWAYS).isEmpty());
    }

    /** A missing decision is a missing answer, not permission. */
    @Test
    void anAbsentRoleDecisionIsRefused() {
        Candidate none = new Candidate(UUID.randomUUID(), true, null, false, false, false,
                true, 12, Long.MIN_VALUE);
        assertTrue(CriminalJobAssigner.roll(none, POLICY, 100L, ALWAYS).isEmpty());
    }

    /** The role gate costs no randomness: a village of guards must not consume the supplier at all. */
    @Test
    void aRejectedRoleConsumesNoRandomness() {
        int[] calls = {0};
        DoubleSupplier counting = () -> {
            calls[0]++;
            return 0.0D;
        };
        CriminalJobAssigner.roll(new Candidate(UUID.randomUUID(), true, LAW, false, false, false,
                true, 12, Long.MIN_VALUE), POLICY, 100L, counting);
        assertEquals(0, calls[0]);
    }
}
