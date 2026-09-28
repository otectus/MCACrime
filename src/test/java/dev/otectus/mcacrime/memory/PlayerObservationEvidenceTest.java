package dev.otectus.mcacrime.memory;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import dev.otectus.mcacrime.state.world.CrimeWorldData;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class PlayerObservationEvidenceTest {
    @Test
    void playerEvidenceIdentityIsStablePerIncidentAndObserver() {
        UUID incident = UUID.randomUUID();
        UUID player = UUID.randomUUID();
        assertEquals(ObservationService.playerObservationId(incident, player),
                ObservationService.playerObservationId(incident, player));
        assertNotEquals(ObservationService.playerObservationId(incident, player),
                ObservationService.playerObservationId(incident, UUID.randomUUID()));
        assertNotEquals(ObservationService.playerObservationId(incident, player),
                ObservationService.playerObservationId(UUID.randomUUID(), player));
    }

    @Test
    void terminalPromotionKeepsUnknownIdentityAndEvidenceId() {
        UUID id = UUID.randomUUID();
        CrimeObservation threat = new CrimeObservation(id, UUID.randomUUID(), UUID.randomUUID(),
                ObserverRole.DIRECT_VICTIM, null, UUID.randomUUID(),
                new ResourceLocation("mcacrime", "attempted_mugging"),
                new ResourceLocation("minecraft", "overworld"), BlockPos.ZERO, 10L, 0F,
                false, true, true, ReportState.PENDING, 100L);
        CrimeObservation completed = threat.withAction(new ResourceLocation("mcacrime", "mugging"));
        assertEquals(id, completed.observationId());
        assertNull(completed.suspectedActorId());
        assertEquals("mugging", completed.actionId().getPath());
        assertTrue(completed.pending());
    }

    @Test
    void playerRetentionDoesNotExpandNpcPendingMemory() {
        CrimeWorldData data = new CrimeWorldData();
        UUID player = UUID.randomUUID();
        UUID npc = UUID.randomUUID();
        for (int i = 0; i < 32; i++) assertTrue(data.addPlayerObservation(observation(player, i)));
        assertFalse(data.addPlayerObservation(observation(player, 32)));
        for (int i = 0; i < 8; i++) assertTrue(data.addObservation(observation(npc, 100 + i)));
        assertFalse(data.addObservation(observation(npc, 108)));
    }

    private static CrimeObservation observation(UUID observer, int seed) {
        return new CrimeObservation(UUID.nameUUIDFromBytes(("observation-" + observer + seed).getBytes()),
                UUID.randomUUID(), observer, ObserverRole.EYEWITNESS, UUID.randomUUID(), null,
                new ResourceLocation("mcacrime", "mugging"),
                new ResourceLocation("minecraft", "overworld"), BlockPos.ZERO, seed, 1F,
                true, true, true, ReportState.PENDING, 1000L);
    }
}
