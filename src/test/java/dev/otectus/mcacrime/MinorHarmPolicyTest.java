package dev.otectus.mcacrime;

import dev.otectus.mcacrime.detect.MinorHarmPolicy;
import dev.otectus.mcacrime.detect.MinorHarmPolicy.Eligibility;
import dev.otectus.mcacrime.detect.MinorHarmPolicy.Facts;
import dev.otectus.mcacrime.detect.MinorHarmPolicy.Settings;
import dev.otectus.mcacrime.detect.MinorHarmPolicy.Verdict;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Minor friendly fire (0.7.7): one light hit is forgiven, nothing after it is.
 *
 * <p>Defaults are the shipped ones: on, 2.0 damage, a 6000-tick window, three victims per attacker.
 */
class MinorHarmPolicyTest {

    private static final Settings DEFAULTS = new Settings(true, 2.0D, 6000L, 3);
    private static final Eligibility CLEAN = new Eligibility(true, true, false, false);

    private final MinorHarmPolicy policy = new MinorHarmPolicy();
    private final UUID attacker = UUID.randomUUID();
    private final UUID villager = UUID.randomUUID();

    private Facts hit(UUID victim, long at, double amount) {
        return new Facts(attacker, victim, at, amount, false, CLEAN);
    }

    @Test
    void anIsolatedLightHitIsForgiven() {
        assertEquals(Verdict.FORGIVEN, policy.assess(DEFAULTS, hit(villager, 100L, 1.0D)));
    }

    @Test
    void theSecondHitInsideTheWindowIsCharged() {
        assertEquals(Verdict.FORGIVEN, policy.assess(DEFAULTS, hit(villager, 100L, 1.0D)));
        assertEquals(Verdict.REPEATED, policy.assess(DEFAULTS, hit(villager, 140L, 1.0D)));
        assertEquals(Verdict.REPEATED, policy.assess(DEFAULTS, hit(villager, 5000L, 0.5D)),
                "every later hit in the window is charged, however light");
    }

    @Test
    void twoHitsInTheSameTickAreOneForgivenAndOneCharged() {
        assertEquals(Verdict.FORGIVEN, policy.assess(DEFAULTS, hit(villager, 100L, 1.0D)));
        assertEquals(Verdict.REPEATED, policy.assess(DEFAULTS, hit(villager, 100L, 1.0D)));
    }

    @Test
    void aLethalHitIsNeverForgiven() {
        assertEquals(Verdict.LETHAL, policy.assess(DEFAULTS,
                new Facts(attacker, villager, 100L, 1.0D, true, CLEAN)));
        assertEquals(Verdict.LETHAL, policy.peek(DEFAULTS, new Facts(attacker, villager, 100L, 0.1D, true, CLEAN)));
    }

    @Test
    void theAttackerVictimCapStopsAStreetOfTaps() {
        for (int i = 0; i < 3; i++) {
            assertEquals(Verdict.FORGIVEN, policy.assess(DEFAULTS, hit(UUID.randomUUID(), 100L + i, 1.0D)));
        }
        assertEquals(Verdict.VICTIM_CAP, policy.assess(DEFAULTS, hit(UUID.randomUUID(), 104L, 1.0D)));
        UUID someoneElse = UUID.randomUUID();
        assertEquals(Verdict.FORGIVEN, policy.assess(DEFAULTS,
                new Facts(someoneElse, villager, 105L, 1.0D, false, CLEAN)), "the cap is per attacker");
    }

    @Test
    void anExistingGrievanceIsNeverForgiven() {
        assertEquals(Verdict.GRIEVANCE, policy.assess(DEFAULTS,
                new Facts(attacker, villager, 100L, 1.0D, false, new Eligibility(true, true, false, true))));
    }

    @Test
    void theWindowExpiresAndGraceReturns() {
        assertEquals(Verdict.FORGIVEN, policy.assess(DEFAULTS, hit(villager, 100L, 1.0D)));
        assertEquals(Verdict.REPEATED, policy.peek(DEFAULTS, hit(villager, 6099L, 1.0D)));
        policy.expire(6100L, DEFAULTS.windowTicks());
        assertEquals(0, policy.size());
        assertEquals(Verdict.FORGIVEN, policy.assess(DEFAULTS, hit(villager, 6100L, 1.0D)));
    }

    @Test
    void aPeekChangesNothing() {
        for (int i = 0; i < 5; i++) {
            assertEquals(Verdict.FORGIVEN, policy.peek(DEFAULTS, hit(villager, 100L + i, 1.0D)));
        }
        assertEquals(0, policy.size());
        assertEquals(Verdict.FORGIVEN, policy.assess(DEFAULTS, hit(villager, 106L, 1.0D)));
    }

    @Test
    void theDamageBoundsAndTheOtherFactsEachRefuse() {
        assertEquals(Verdict.TOO_HEAVY, policy.peek(DEFAULTS, hit(villager, 100L, 2.5D)));
        assertEquals(Verdict.FORGIVEN, policy.peek(DEFAULTS, hit(villager, 100L, 2.0D)), "the maximum itself is minor");
        assertEquals(Verdict.NO_DAMAGE, policy.peek(DEFAULTS, hit(villager, 100L, 0.0D)));
        assertEquals(Verdict.NO_DAMAGE, policy.peek(DEFAULTS, hit(villager, 100L, Double.NaN)));
        assertEquals(Verdict.NOT_PROTECTED, policy.peek(DEFAULTS,
                new Facts(attacker, villager, 100L, 1.0D, false, new Eligibility(false, true, false, false))));
        assertEquals(Verdict.NOT_OWN_HIT, policy.peek(DEFAULTS,
                new Facts(attacker, villager, 100L, 1.0D, false, new Eligibility(true, false, false, false))),
                "a pet's bite is not the owner's accident");
        assertEquals(Verdict.MUGGING, policy.peek(DEFAULTS,
                new Facts(attacker, villager, 100L, 1.0D, false, new Eligibility(true, true, true, false))));
        assertEquals(Verdict.DISABLED, policy.peek(new Settings(false, 2.0D, 6000L, 3), hit(villager, 100L, 1.0D)));
        assertEquals(Verdict.DISABLED, policy.peek(Settings.OFF, hit(villager, 100L, 1.0D)));
    }

    @Test
    void aRefusedHitStillOpensTheWindowForThePair() {
        assertEquals(Verdict.TOO_HEAVY, policy.assess(DEFAULTS, hit(villager, 100L, 6.0D)));
        assertEquals(Verdict.REPEATED, policy.assess(DEFAULTS, hit(villager, 200L, 1.0D)),
                "a light tap after a heavy blow is not an accident");
    }
}
