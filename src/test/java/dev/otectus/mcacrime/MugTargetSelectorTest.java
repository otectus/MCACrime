package dev.otectus.mcacrime;

import dev.otectus.mcacrime.ai.thief.GuardRisk;
import dev.otectus.mcacrime.ai.thief.MugTargetSelector;
import dev.otectus.mcacrime.ai.thief.MugTargetSelector.Scored;
import dev.otectus.mcacrime.ai.thief.MugTargetSelector.VictimCandidate;
import dev.otectus.mcacrime.ai.thief.ThiefPolicy;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Victim eligibility and the opportunity score (0.5.1, spec §"Victim selection").
 *
 * <p>Eligibility is the half worth being strict about. Spec §"Player mugging interaction" makes one
 * rule load-bearing — a player already holding a qualifying weapon cannot be selected — and the rest
 * of the row (creative, spectator, invulnerable, already somebody else's victim, configured immune)
 * is the same kind of promise. None of them may be traded away by a high enough score, so every one
 * is asserted against a candidate that is otherwise perfect.
 */
class MugTargetSelectorTest {

    private static final ThiefPolicy POLICY = new ThiefPolicy(80, 12000, 30, 20.0, 16.0, 8.0, 0.6);
    private static final GuardRisk CLEAR = GuardRisk.none();
    private static final UUID ID = UUID.nameUUIDFromBytes("victim".getBytes());

    /** An unarmed survival player standing alone, eight blocks away and visible. */
    private static VictimCandidate ideal(UUID id) {
        return new VictimCandidate(id, 8.0, false, false, false, false, false, true, 0, 16L, 0);
    }

    private static Optional<Scored> select(VictimCandidate... candidates) {
        return MugTargetSelector.select(List.of(candidates), CLEAR, POLICY);
    }

    @Test
    void anUnarmedSurvivalPlayerIsEligible() {
        assertTrue(MugTargetSelector.eligible(ideal(ID), POLICY));
        assertEquals(ID, select(ideal(ID)).orElseThrow().id());
    }

    @Test
    void everyDisqualifyingFactRefusesAnOtherwisePerfectVictim() {
        VictimCandidate base = ideal(ID);
        assertIneligible(new VictimCandidate(ID, 8.0, true, false, false, false, false, true, 0, 16L, 0),
                "already holding a qualifying weapon");
        assertIneligible(new VictimCandidate(ID, 8.0, false, true, false, false, false, true, 0, 16L, 0),
                "creative or spectator");
        assertIneligible(new VictimCandidate(ID, 8.0, false, false, true, false, false, true, 0, 16L, 0),
                "invulnerable");
        assertIneligible(new VictimCandidate(ID, 8.0, false, false, false, true, false, true, 0, 16L, 0),
                "already the victim of another thief");
        assertIneligible(new VictimCandidate(ID, 8.0, false, false, false, false, true, true, 0, 16L, 0),
                "configured immune");
        assertIneligible(new VictimCandidate(ID, 8.0, false, false, false, false, false, false, 0, 16L, 0),
                "no line of sight");
        assertIneligible(new VictimCandidate(ID, 64.0, false, false, false, false, false, true, 0, 16L, 0),
                "outside the acquisition radius");
        // The control: the row every one of the above was derived from is still fine.
        assertTrue(MugTargetSelector.eligible(base, POLICY));
    }

    private static void assertIneligible(VictimCandidate candidate, String why) {
        assertFalse(MugTargetSelector.eligible(candidate, POLICY), "should be ineligible: " + why);
        assertTrue(select(candidate).isEmpty(), "should not be selected: " + why);
    }

    @Test
    void aVisibleGuardRefusesEveryTargetRatherThanScoringThemDown() {
        GuardRisk watched = new GuardRisk(1, true, 6.0, 1.4);
        assertTrue(MugTargetSelector.select(List.of(ideal(ID)), watched, POLICY).isEmpty(),
                "guard risk past the threshold must refuse the target outright");
    }

    @Test
    void theSecondThiefGetsNobodyWhenTheOnlyVictimIsAlreadyBeingRobbed() {
        VictimCandidate taken = new VictimCandidate(ID, 8.0, false, false, false, true, false, true, 0, 16L, 0);
        assertTrue(select(taken).isEmpty());
    }

    @Test
    void anIsolatedNearbyVictimOutscoresACrowdedDistantOne() {
        UUID alone = UUID.nameUUIDFromBytes("alone".getBytes());
        UUID crowded = UUID.nameUUIDFromBytes("crowded".getBytes());
        VictimCandidate a = new VictimCandidate(alone, 9.0, false, false, false, false, false, true, 0, 4L, 0);
        VictimCandidate b = new VictimCandidate(crowded, 4.0, false, false, false, false, false, true, 3, 40L, 0);
        assertEquals(alone, select(a, b).orElseThrow().id());
    }

    @Test
    void repeatedFailuresPushAVictimDownTheList() {
        UUID fresh = UUID.nameUUIDFromBytes("fresh".getBytes());
        UUID burned = UUID.nameUUIDFromBytes("burned".getBytes());
        VictimCandidate a = new VictimCandidate(fresh, 12.0, false, false, false, false, false, true, 0, 0L, 0);
        VictimCandidate b = new VictimCandidate(burned, 6.0, false, false, false, false, false, true, 0, 0L, 4);
        assertEquals(fresh, select(a, b).orElseThrow().id());
    }

    @Test
    void anEmptyCrowdSelectsNobody() {
        assertTrue(select().isEmpty());
    }
}
