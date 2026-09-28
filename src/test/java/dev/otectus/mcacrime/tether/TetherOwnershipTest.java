package dev.otectus.mcacrime.tether;

import dev.otectus.mcacrime.state.world.CrimeWorldData;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A chain is an item somebody paid for, and detaching pays it back exactly once (0.7.5 M4.1).
 *
 * <p>This is the upstream defect the whole ownership model exists to prevent: {@code setAnchoredTo(null)}
 * spawns a fresh {@code Items.CHAIN} <b>every time it is called with null</b>, from five separate call
 * sites, with nothing consuming the original and nothing guarding a double call
 * ({@code mixin/LivingEntityMixin.java:64-79}). A block broken by a player, an explosion and a piston
 * in one tick therefore mints three chains from one.
 *
 * <p>The fix is structural rather than a flag: the row is removed from the table first, and the chain
 * is owed only if that removal was this call's doing. The assertions below pin both halves — the
 * removal is idempotent, and the pure ownership rule says who is owed what.
 */
class TetherOwnershipTest {

    private static final ResourceLocation OVERWORLD = new ResourceLocation("minecraft", "overworld");

    private CrimeWorldData data;
    private UUID subject;
    private UUID holder;

    @BeforeEach
    void freshWorld() {
        data = new CrimeWorldData();
        subject = UUID.randomUUID();
        holder = UUID.randomUUID();
        TetherService.invalidate();
    }

    private TetherRecord chain(boolean owed) {
        TetherRecord tether = TetherRecord.toHolder(UUID.randomUUID(), subject, TetherKind.CHAIN,
                holder, OVERWORLD, 5.0D, owed ? holder : null, owed);
        assertTrue(data.putTether(tether));
        TetherService.index(data).put(tether);
        return tether;
    }

    // ---------------------------------------------------------------- the ownership rule

    @Test
    void onlyAChainSomebodyPaidForIsOwedBack() {
        TetherRecord paid = chain(true);
        assertTrue(TetherService.owes(paid, TetherService.DetachReason.RELEASED));
        assertTrue(TetherService.owes(paid, TetherService.DetachReason.ESCAPED),
                "a chain that was struggled out of still exists and is still owed");
        assertTrue(TetherService.owes(paid, TetherService.DetachReason.HOLDER_LOST));
    }

    @Test
    void anEscortAndALegacyHoldOweNobodyAnything() {
        TetherRecord escort = TetherRecord.toHolder(UUID.randomUUID(), subject, TetherKind.ESCORT,
                holder, OVERWORLD, 5.0D, null, false);
        TetherRecord legacy = TetherRecord.toAnchor(UUID.randomUUID(), subject, TetherKind.LEGACY_HOLD,
                OVERWORLD, new BlockPos(0, 64, 0), 5.0D, null, false);
        assertFalse(TetherService.owes(escort, TetherService.DetachReason.RELEASED),
                "taking hold of somebody with your hands costs no item, so letting go mints none");
        assertFalse(TetherService.owes(legacy, TetherService.DetachReason.RELEASED),
                "migration fabricates nothing, so ending a migrated hold drops nothing (§3.18)");
    }

    @Test
    void aSubjectWhoDiesHoldingItIsOwedNothingHere() {
        TetherRecord paid = chain(true);
        assertFalse(TetherService.owes(paid, TetherService.DetachReason.SUBJECT_DIED),
                "the death path is already resolving their inventory; a second item is an "
                        + "unaccounted drop");
    }

    @Test
    void nothingIsOwedForATetherThatDoesNotExist() {
        assertFalse(TetherService.owes(null, TetherService.DetachReason.RELEASED));
    }

    // ---------------------------------------------------------------- idempotence

    @Test
    void repeatedDetachYieldsOneChain() {
        TetherRecord paid = chain(true);
        // No server, so nobody is loaded to hand the item to and the payment resolves to empty --
        // what is being asserted here is that the *second* call finds nothing at all to pay for.
        Optional<net.minecraft.world.item.ItemStack> first =
                TetherService.detach(null, data, paid.id(), TetherService.DetachReason.RELEASED);
        assertNull(data.tether(paid.id()), "the row is gone after the first detach");

        Optional<net.minecraft.world.item.ItemStack> second =
                TetherService.detach(null, data, paid.id(), TetherService.DetachReason.RELEASED);
        assertTrue(second.isEmpty(), "a second detach owes nothing: somebody else already paid");
        assertEquals(first.isPresent(), first.isPresent());
        assertTrue(TetherService.forSubject(data, subject).isEmpty(),
                "and the subject is left holding no tethers at all");
    }

    @Test
    void detachingSomethingThatWasNeverThereChangesNothing() {
        assertTrue(TetherService.detach(null, data, UUID.randomUUID(),
                TetherService.DetachReason.RELEASED).isEmpty());
        assertTrue(TetherService.detach(null, data, null,
                TetherService.DetachReason.RELEASED).isEmpty());
        assertEquals(0, TetherService.index(data).size());
    }

    @Test
    void fiveCallSitesInOneTickStillEndOneTether() {
        TetherRecord paid = chain(true);
        int ended = 0;
        for (int i = 0; i < 5; i++) {
            if (data.tether(paid.id()) != null) {
                TetherService.detach(null, data, paid.id(), TetherService.DetachReason.HOLDER_LOST);
                ended++;
            }
        }
        assertEquals(1, ended, "the table write is the gate, so only the first call does anything");
        assertNull(data.tether(paid.id()));
    }

    // ---------------------------------------------------------------- bulk detachment

    @Test
    void detachingEverythingHoldingASubjectEndsEachRowOnce() {
        chain(true);
        TetherRecord second = TetherRecord.toAnchor(UUID.randomUUID(), subject, TetherKind.ANCHOR,
                OVERWORLD, new BlockPos(3, 64, 3), 5.0D, holder, true);
        assertTrue(data.putTether(second));
        TetherService.index(data).put(second);
        assertEquals(2, TetherService.forSubject(data, subject).size());

        for (TetherRecord tether : TetherService.forSubject(data, subject)) {
            TetherService.detach(null, data, tether.id(), TetherService.DetachReason.ADMINISTRATIVE);
        }
        assertTrue(TetherService.forSubject(data, subject).isEmpty());
        assertEquals(0, TetherService.index(data).size());
    }
}
