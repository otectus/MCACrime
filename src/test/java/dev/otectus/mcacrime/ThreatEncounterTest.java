package dev.otectus.mcacrime;

import dev.otectus.mcacrime.detect.CombatEncounters;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A mugging is aggression that never lands a blow (0.7.7): an encounter the victim opens by answering
 * it belongs to the thief, so the victim's force is self-defence and a killing is excessive force rather
 * than an unprovoked one. Under NORMAL_LAW these are the only rules that decide the victim's blows.
 */
class ThreatEncounterTest {

    private static final UUID VICTIM = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID THIEF = UUID.fromString("00000000-0000-0000-0000-000000000002");

    @Test
    void theVictimsFirstBlowIsSelfDefence() {
        CombatEncounters encounters = new CombatEncounters();
        var decision = encounters.record(VICTIM, THIEF, 100L, false, false, false, true);
        assertEquals(CombatEncounters.Basis.SELF_DEFENSE, decision.basis());
        assertEquals(THIEF, decision.initiator());
        assertTrue(decision.exempt());
        var again = encounters.record(VICTIM, THIEF, 110L, false, false, false, true);
        assertEquals(CombatEncounters.Basis.SELF_DEFENSE, again.basis(), "every blow of the same fight");
    }

    @Test
    void killingTheMuggerIsExcessiveDefensiveForce() {
        CombatEncounters encounters = new CombatEncounters();
        encounters.record(VICTIM, THIEF, 100L, false, false, false, true);
        var lethal = encounters.record(VICTIM, THIEF, 120L, true, false, false, true);
        assertEquals(CombatEncounters.Basis.EXCESSIVE_DEFENSIVE_FORCE, lethal.basis());
        assertFalse(lethal.exempt());
        var outright = new CombatEncounters().record(VICTIM, THIEF, 100L, true, false, false, true);
        assertEquals(CombatEncounters.Basis.EXCESSIVE_DEFENSIVE_FORCE, outright.basis(), "a one-blow killing too");
    }

    @Test
    void withoutAThreatTheFirstBlowIsStillTheAttackers() {
        var decision = new CombatEncounters().record(VICTIM, THIEF, 100L, false, false, false, false);
        assertEquals(CombatEncounters.Basis.INITIATING_AGGRESSION, decision.basis());
        assertFalse(decision.exempt());
    }

    @Test
    void anExistingEncounterKeepsItsInitiator() {
        CombatEncounters encounters = new CombatEncounters();
        encounters.record(VICTIM, THIEF, 100L, false, false, false, false);
        var decision = encounters.record(VICTIM, THIEF, 110L, false, false, false, true);
        assertEquals(VICTIM, decision.initiator(), "a threat cannot rewrite a fight the player already started");
        assertEquals(CombatEncounters.Basis.CONTINUED_AGGRESSION, decision.basis());
    }
}
