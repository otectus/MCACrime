package dev.otectus.mcacrime.enforcement;

import dev.otectus.mcacrime.state.world.CrimeWorldData;
import dev.otectus.mcacrime.tether.TetherKind;
import dev.otectus.mcacrime.tether.TetherRecord;
import dev.otectus.mcacrime.tether.TetherService;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guard death, logout and dimension change: a documented end, never a stranded subject (0.7.5 M4.3).
 *
 * <p>Specification §21.3's {@code captorLogoutGuardDeathDimensionChange}. Each of the three has one
 * thing in common — the entity at the far end of the hold is gone — and one rule: the hold ends, the
 * subject is left free rather than pinned to a holder who no longer exists, and an escort owes nobody
 * a chain on the way out because nobody supplied one.
 */
class EscortHandoverTest {

    private static final ResourceLocation OVERWORLD = ResourceLocation.fromNamespaceAndPath("minecraft", "overworld");

    private CrimeWorldData data;
    private UUID prisoner;
    private UUID guard;

    @BeforeEach
    void freshWorld() {
        data = new CrimeWorldData();
        prisoner = UUID.randomUUID();
        guard = UUID.randomUUID();
        TetherService.invalidate();
    }

    private TetherRecord escort(UUID holder) {
        TetherRecord tether = TetherRecord.toHolder(UUID.randomUUID(), prisoner, TetherKind.ESCORT,
                holder, OVERWORLD, 5.0D, null, false);
        assertTrue(data.putTether(tether));
        TetherService.index(data).put(tether);
        return tether;
    }

    @Test
    void aGuardDyingEndsTheHoldsTheyWereLeading() {
        TetherRecord tether = escort(guard);
        assertEquals(1, TetherService.index(data).heldBy(guard));

        for (TetherRecord held : TetherService.index(data).forHolder(guard)) {
            TetherService.detach(null, data, held.id(), TetherService.DetachReason.HOLDER_LOST);
        }
        assertNull(data.tether(tether.id()));
        assertEquals(0, TetherService.index(data).heldBy(guard));
        assertTrue(TetherService.forSubject(data, prisoner).isEmpty(),
                "the prisoner is free rather than pinned to somebody who no longer exists");
    }

    @Test
    void anEscortOwesNobodyAChainWhenItEnds() {
        TetherRecord tether = escort(guard);
        assertFalse(TetherService.owes(tether, TetherService.DetachReason.HOLDER_LOST),
                "taking hold of somebody with your hands costs no item");
        assertTrue(TetherService.detach(null, data, tether.id(),
                TetherService.DetachReason.HOLDER_LOST).isEmpty());
    }

    @Test
    void reassignmentMovesTheHoldRatherThanStackingASecondOne() {
        escort(guard);
        UUID replacement = UUID.randomUUID();
        // What EscortService.reassign does: end the old hold, take the new one.
        for (TetherRecord held : TetherService.index(data).forHolder(guard)) {
            TetherService.detach(null, data, held.id(), TetherService.DetachReason.HOLDER_LOST);
        }
        escort(replacement);

        assertEquals(1, data.tethers().size(), "one hold, one holder");
        assertEquals(0, TetherService.index(data).heldBy(guard));
        assertEquals(1, TetherService.index(data).heldBy(replacement));
    }

    @Test
    void reIssuingAnEscortEveryScanDoesNotStackTethers() {
        // The enforcement scan calls escort() on every pass. The index is what makes that idempotent:
        // a pair that already has an ESCORT row is renewed, never duplicated.
        escort(guard);
        assertEquals(1, TetherService.forSubject(data, prisoner).size());
        assertEquals(guard, TetherService.active(data, prisoner).orElseThrow().holder());
    }

    @Test
    void aDimensionChangeEndsTheHoldRatherThanFollowingIt() {
        TetherRecord tether = escort(guard);
        // A tether never spans dimensions: the far end is in another world and the distance is
        // undefined, so the hold is ended rather than evaluated.
        TetherService.detachAll(null, data, prisoner, TetherService.DetachReason.HOLDER_LOST);
        assertNull(data.tether(tether.id()));
    }

    @Test
    void endingAnEscortLeavesAChainInPlaceAndUnsuspended() {
        TetherRecord escortRow = escort(guard);
        TetherRecord chain = TetherRecord.toHolder(UUID.randomUUID(), prisoner, TetherKind.CHAIN,
                UUID.randomUUID(), OVERWORLD, 5.0D, prisoner, true).suspended(true);
        assertTrue(data.putTether(chain));
        TetherService.index(data).put(chain);

        TetherService.detach(null, data, escortRow.id(), TetherService.DetachReason.ADMINISTRATIVE);
        TetherService.suspend(data, chain.id(), false);

        assertEquals(1, data.tethers().size(), "the chain is still there");
        assertFalse(data.tether(chain.id()).suspended(),
                "and it is holding them again now that the escort has ended");
    }
}
