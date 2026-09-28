package dev.otectus.mcacrime.tether;

import dev.otectus.mcacrime.activity.CrimeActivityRegistry;
import dev.otectus.mcacrime.state.world.CrimeWorldData;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exactly one authority per subject, and the losers are suspended rather than dropped (0.7.5 M4.3).
 *
 * <p>Specification §9.4's ladder: an occupied detention device, then a seat or mount, then a close
 * escort, then chain tension, then the subject's own movement. The rule the suspension half exists for
 * is the one a player would report: closing a pillory on somebody chained to a fence must not drop the
 * chain on the floor, and opening it again must give the chain back.
 */
class TransportArbiterTest {

    private static final ResourceLocation OVERWORLD = ResourceLocation.fromNamespaceAndPath("minecraft", "overworld");

    private CrimeWorldData data;
    private UUID subject;

    @BeforeEach
    void freshWorld() {
        data = new CrimeWorldData();
        subject = UUID.randomUUID();
        TetherService.invalidate();
    }

    // ---------------------------------------------------------------- the ladder

    @Test
    void aDeviceOutranksEverything() {
        assertEquals(TransportArbiter.Authority.DETENTION,
                TransportArbiter.resolve(true, true, true, true));
        assertEquals(TransportArbiter.Authority.DETENTION,
                TransportArbiter.resolve(true, false, false, false));
    }

    @Test
    void aVehicleOutranksAnEscortAndAChain() {
        assertEquals(TransportArbiter.Authority.VEHICLE,
                TransportArbiter.resolve(false, true, true, true),
                "a chained passenger goes where the boat goes");
    }

    @Test
    void anEscortOutranksAChain() {
        assertEquals(TransportArbiter.Authority.ESCORT,
                TransportArbiter.resolve(false, false, true, true),
                "an escorted subject who is also chained to a fence is walked by the escort");
    }

    @Test
    void aChainActsOnlyWhenNothingElseDoes() {
        assertEquals(TransportArbiter.Authority.CHAIN,
                TransportArbiter.resolve(false, false, false, true));
    }

    @Test
    void anUnheldSubjectIsFree() {
        assertEquals(TransportArbiter.Authority.FREE,
                TransportArbiter.resolve(false, false, false, false));
    }

    // ---------------------------------------------------------------- suspension

    @Test
    void aDeviceOrAVehicleSuspendsEveryTether() {
        for (TetherKind kind : TetherKind.values()) {
            assertTrue(TransportArbiter.suspends(TransportArbiter.Authority.DETENTION, kind), kind.name());
            assertTrue(TransportArbiter.suspends(TransportArbiter.Authority.VEHICLE, kind), kind.name());
        }
    }

    @Test
    void anEscortSuspendsTheChainButNotItself() {
        assertFalse(TransportArbiter.suspends(TransportArbiter.Authority.ESCORT, TetherKind.ESCORT));
        assertTrue(TransportArbiter.suspends(TransportArbiter.Authority.ESCORT, TetherKind.CHAIN));
        assertTrue(TransportArbiter.suspends(TransportArbiter.Authority.ESCORT, TetherKind.ANCHOR));
    }

    @Test
    void aChainOrAFreeSubjectSuspendsNothing() {
        for (TetherKind kind : TetherKind.values()) {
            assertFalse(TransportArbiter.suspends(TransportArbiter.Authority.CHAIN, kind), kind.name());
            assertFalse(TransportArbiter.suspends(TransportArbiter.Authority.FREE, kind), kind.name());
        }
        assertFalse(TransportArbiter.suspends(TransportArbiter.Authority.DETENTION, null));
    }

    @Test
    void aSuspendedRelationshipIsRestoredRatherThanRecreated() {
        TetherRecord chain = new TetherRecord(UUID.randomUUID(), subject, TetherKind.ANCHOR,
                UUID.randomUUID(), OVERWORLD, new BlockPos(2, 64, 2), 5.0D, false, subject, true, 1L);
        assertTrue(data.putTether(chain));
        TetherService.index(data).put(chain);

        assertTrue(TetherService.suspend(data, chain.id(), true));
        assertTrue(data.tether(chain.id()).suspended());
        assertTrue(TetherService.active(data, subject).isEmpty(),
                "a suspended chain is not what is holding them right now");
        assertEquals(1, data.tethers().size(), "but it still exists: the chain is not on the floor");

        assertTrue(TetherService.suspend(data, chain.id(), false));
        assertFalse(data.tether(chain.id()).suspended());
        assertTrue(TetherService.active(data, subject).isPresent(),
                "releasing the device restores the chain rather than dropping it");
        assertFalse(TetherService.suspend(data, chain.id(), false), "and doing it twice is a no-op");
    }

    // ---------------------------------------------------------------- handover

    @Test
    void aChangedClaimGenerationHandsTheSubjectOver() {
        assertTrue(TransportArbiter.claimStillOurs(7L, 7L));
        assertFalse(TransportArbiter.claimStillOurs(7L, 8L),
                "a guard reassignment, a custody transfer and a captor's death all look like this");
        assertFalse(TransportArbiter.claimStillOurs(CrimeActivityRegistry.REFUSED,
                CrimeActivityRegistry.REFUSED), "a refused claim was never ours to begin with");
    }
}
