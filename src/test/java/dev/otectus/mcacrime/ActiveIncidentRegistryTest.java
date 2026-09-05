package dev.otectus.mcacrime;

import dev.otectus.mcacrime.enforcement.ActiveIncidentRegistry;
import dev.otectus.mcacrime.ledger.CrimeFlag;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The registry guards read to decide whether a crime is happening right now.
 *
 * <p>The phase gating is the test spec §"Guard intervention" ends on: a thief that scouts and
 * approaches but never threatens must never be arrested, and the way that holds is that those two
 * stages open no incident at all. A guard asking the registry about such a thief gets nothing back,
 * which is the mechanical form of "guards are not psychic".
 */
class ActiveIncidentRegistryTest {

    private final UUID thief = UUID.randomUUID();
    private final UUID victim = UUID.randomUUID();

    @BeforeEach
    @AfterEach
    void reset() {
        ActiveIncidentRegistry.clearAll();
    }

    private ActiveIncidentRegistry.ActiveIncident incident(ActiveIncidentRegistry.Phase phase) {
        // The dimension key is carried, never dereferenced, and building a real one would need the
        // vanilla registries bootstrapped for no gain -- the registry keys on the offender alone.
        return new ActiveIncidentRegistry.ActiveIncident(UUID.randomUUID(), thief, victim, null,
                100L, EnumSet.of(CrimeFlag.NPC_OFFENDER), phase);
    }

    @Test
    void aThiefThatNeverThreatensIsInvisibleToGuards() {
        // Scouting and approaching are states of the thief's own controller; neither opens an
        // incident, so there is nothing here for enforcement to find.
        assertTrue(ActiveIncidentRegistry.get(thief).isEmpty());
        assertTrue(ActiveIncidentRegistry.all().isEmpty());
    }

    @Test
    void everyPhaseThatCanBeOpenedIsActionable() {
        for (ActiveIncidentRegistry.Phase phase : ActiveIncidentRegistry.Phase.values()) {
            assertTrue(ActiveIncidentRegistry.visibleToGuards(phase),
                    phase + " is an open incident, so a guard must be allowed to act on it");
        }
    }

    @Test
    void openIsIdempotentPerOffender() {
        ActiveIncidentRegistry.open(incident(ActiveIncidentRegistry.Phase.THREAT));
        ActiveIncidentRegistry.open(incident(ActiveIncidentRegistry.Phase.THREAT));
        assertEquals(1, ActiveIncidentRegistry.all().size());
        assertTrue(ActiveIncidentRegistry.get(thief).isPresent());
    }

    @Test
    void closeIsIdempotentAndReturnsTheIncidentOnce() {
        ActiveIncidentRegistry.open(incident(ActiveIncidentRegistry.Phase.THREAT));
        assertTrue(ActiveIncidentRegistry.close(thief).isPresent());
        assertTrue(ActiveIncidentRegistry.close(thief).isEmpty());
        assertTrue(ActiveIncidentRegistry.get(thief).isEmpty());
    }

    @Test
    void advanceMovesTheOpenIncidentAndIgnoresUnknownOffenders() {
        ActiveIncidentRegistry.open(incident(ActiveIncidentRegistry.Phase.THREAT));
        ActiveIncidentRegistry.advance(thief, ActiveIncidentRegistry.Phase.COMMITTED);
        assertEquals(ActiveIncidentRegistry.Phase.COMMITTED,
                ActiveIncidentRegistry.get(thief).orElseThrow().phase());

        ActiveIncidentRegistry.advance(UUID.randomUUID(), ActiveIncidentRegistry.Phase.COMMITTED);
        assertEquals(1, ActiveIncidentRegistry.all().size());
    }

    @Test
    void aNullOffenderIsNeverAnOpenIncident() {
        assertTrue(ActiveIncidentRegistry.get(null).isEmpty());
        assertTrue(ActiveIncidentRegistry.close(null).isEmpty());
        assertFalse(ActiveIncidentRegistry.all().iterator().hasNext());
    }
}
