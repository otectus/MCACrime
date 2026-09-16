package dev.otectus.mcacrime;

import dev.otectus.mcacrime.effect.SandIncidentPolicy;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** What one sand impact owes the legal system, and what it deliberately does not (0.7.2 §14). */
class SandIncidentTest {

    private static final UUID THROWER = UUID.fromString("00000000-0000-0000-0000-00000000000f");
    private static final UUID VICTIM_A = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final UUID VICTIM_B = UUID.fromString("00000000-0000-0000-0000-0000000000b1");
    private static final UUID WITNESS = UUID.fromString("00000000-0000-0000-0000-0000000000e1");
    private static final UUID LAUNCH = UUID.fromString("00000000-0000-0000-0000-0000000000fa");

    private static SandIncidentPolicy.LaunchSnapshot launch(boolean masked, Set<UUID> witnesses) {
        return SandIncidentPolicy.LaunchSnapshot.of(THROWER, LAUNCH, 4200L, masked, witnesses);
    }

    @Test
    void eachVictimOfOneImpactGetsItsOwnStableIncidentId() {
        var one = SandIncidentPolicy.incidentIdFor(LAUNCH, VICTIM_A);
        var again = SandIncidentPolicy.incidentIdFor(LAUNCH, VICTIM_A);
        var other = SandIncidentPolicy.incidentIdFor(LAUNCH, VICTIM_B);
        assertEquals(one, again, "a replayed impact must collide with the record it already wrote");
        assertNotEquals(one, other,
                "two victims sharing one id would have the second silently deduplicated away");
    }

    @Test
    void twoDifferentThrowsAgainstTheSameVictimAreDifferentIncidents() {
        assertNotEquals(SandIncidentPolicy.incidentIdFor(LAUNCH, VICTIM_A),
                SandIncidentPolicy.incidentIdFor(UUID.randomUUID(), VICTIM_A));
    }

    @Test
    void severalLawfulVictimsInOneBurstEachProduceExactlyOneCharge() {
        List<SandIncidentPolicy.Charge> charges = SandIncidentPolicy.charges(launch(false, Set.of()),
                List.of(new SandIncidentPolicy.Exposure(VICTIM_A, true, false, false),
                        new SandIncidentPolicy.Exposure(VICTIM_B, true, false, false)));
        assertEquals(List.of(VICTIM_A, VICTIM_B),
                charges.stream().map(SandIncidentPolicy.Charge::victim).toList());
    }

    @Test
    void aVictimWhoseEffectWasVetoedIsNotReportedAsBlinded() {
        assertTrue(SandIncidentPolicy.charges(launch(false, Set.of()),
                List.of(new SandIncidentPolicy.Exposure(VICTIM_A, false, false, false))).isEmpty(),
                "another mod cancelling the effect means nobody was blinded, so nobody was wronged");
    }

    @Test
    void catchingYourselfInYourOwnSplashIsNeverACrime() {
        assertTrue(SandIncidentPolicy.charges(launch(false, Set.of()),
                List.of(new SandIncidentPolicy.Exposure(THROWER, true, true, false))).isEmpty());
        // Even if the self-exposure flag were somehow lost, the thrower's own id is refused as well.
        assertTrue(SandIncidentPolicy.charges(launch(false, Set.of()),
                List.of(new SandIncidentPolicy.Exposure(THROWER, true, false, false))).isEmpty());
    }

    @Test
    void sandingAnActiveMuggerIsDefensiveAndCarriesNoCharge() {
        // The flag has to be computed before the effect lands: applying it aborts the mugging, and the
        // aborted session is the evidence that the throw was defensive.
        assertTrue(SandIncidentPolicy.charges(launch(false, Set.of()),
                List.of(new SandIncidentPolicy.Exposure(VICTIM_A, true, false, true))).isEmpty());
        assertEquals(1, SandIncidentPolicy.charges(launch(false, Set.of()),
                List.of(new SandIncidentPolicy.Exposure(VICTIM_A, true, false, false))).size(),
                "an uninvolved villager is not made lawful by the item doing no damage");
    }

    @Test
    void oneVictimDeliveredTwiceIsStillOneCharge() {
        assertEquals(1, SandIncidentPolicy.charges(launch(false, Set.of()),
                List.of(new SandIncidentPolicy.Exposure(VICTIM_A, true, false, false),
                        new SandIncidentPolicy.Exposure(VICTIM_A, true, false, false))).size());
    }

    @Test
    void theCommittedIdentityIsTheLaunchOneNotWhateverIsWornAtImpact() {
        var masked = launch(true, Set.of(WITNESS));
        assertTrue(masked.maskedAtLaunch(),
                "changing mask during flight cannot rewrite an observation of the throw");
        assertEquals(Set.of(WITNESS), masked.witnesses(),
                "the witnesses are the ones who saw the throw, not the ones standing where it landed");
        assertEquals(4200L, masked.launchTick());
        assertTrue(SandIncidentPolicy.usesLaunchObservations(masked));
    }

    @Test
    void aThrowNobodySawIsStillAThrowWithAnEmptyWitnessSet() {
        var unseen = launch(false, Set.of());
        assertTrue(unseen.witnesses().isEmpty());
        assertEquals(1, SandIncidentPolicy.charges(unseen,
                List.of(new SandIncidentPolicy.Exposure(VICTIM_A, true, false, false))).size(),
                "an unwitnessed crime is still a crime; it is the Heat that changes, not the record");
    }
}
