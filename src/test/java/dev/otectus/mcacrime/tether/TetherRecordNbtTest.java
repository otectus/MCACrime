package dev.otectus.mcacrime.tether;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** One tether, through NBT and back, including the two things that end up owing somebody an item. */
class TetherRecordNbtTest {

    private static final ResourceLocation OVERWORLD = ResourceLocation.fromNamespaceAndPath("minecraft", "overworld");

    @Test
    void aHeldTetherRoundTrips() {
        UUID id = UUID.randomUUID();
        UUID subject = UUID.randomUUID();
        UUID holder = UUID.randomUUID();
        UUID chainOwner = UUID.randomUUID();
        TetherRecord tether = TetherRecord.toHolder(id, subject, TetherKind.CHAIN, holder, OVERWORLD,
                5.0D, chainOwner, true);

        TetherRecord loaded = TetherRecord.load(tether.save()).orElseThrow();

        assertEquals(tether, loaded);
        assertEquals(holder, loaded.holderId().orElseThrow());
        assertEquals(Optional.empty(), loaded.anchor());
        assertEquals(chainOwner, loaded.chainOwner());
        assertTrue(loaded.returnOnRelease(), "somebody paid for that chain and gets it back");
        assertTrue(loaded.valid());
    }

    @Test
    void anAnchoredTetherRoundTrips() {
        TetherRecord tether = TetherRecord.toAnchor(UUID.randomUUID(), UUID.randomUUID(),
                TetherKind.ANCHOR, OVERWORLD, new BlockPos(-40, 70, 12), 3.0D, null, false);

        TetherRecord loaded = TetherRecord.load(tether.save()).orElseThrow();

        assertEquals(tether, loaded);
        assertEquals(new BlockPos(-40, 70, 12), loaded.anchor().orElseThrow());
        assertEquals(Optional.empty(), loaded.holderId());
        assertEquals(OVERWORLD, loaded.dimension());
    }

    /** A migrated hold owes nobody an item, because nobody ever supplied one (§3.18). */
    @Test
    void aLegacyHoldCarriesNoChainOwnership() {
        TetherRecord tether = TetherRecord.toAnchor(UUID.randomUUID(), UUID.randomUUID(),
                TetherKind.LEGACY_HOLD, OVERWORLD, new BlockPos(0, 64, 0), 6.0D, null, false);

        TetherRecord loaded = TetherRecord.load(tether.save()).orElseThrow();

        assertEquals(TetherKind.LEGACY_HOLD, loaded.kind());
        assertFalse(loaded.returnOnRelease());
        assertEquals(null, loaded.chainOwner());
    }

    @Test
    void suspensionIsAStateChangeRatherThanADeletion() {
        TetherRecord tether = TetherRecord.toHolder(UUID.randomUUID(), UUID.randomUUID(),
                TetherKind.ESCORT, UUID.randomUUID(), OVERWORLD, 4.0D, null, false);

        TetherRecord suspended = tether.suspended(true);

        assertTrue(suspended.suspended());
        assertEquals(tether.revision() + 1L, suspended.revision());
        assertFalse(tether.suspended(), "the original is immutable");
        assertEquals(suspended, suspended.suspended(true), "setting it twice changes nothing");
        assertEquals(TetherRecord.load(suspended.save()).orElseThrow(), suspended,
                "a device release has to be able to restore the chain, so the row must survive");
    }

    @Test
    void lengthIsBoundedAndNeverNonFinite() {
        TetherRecord absurd = TetherRecord.toHolder(UUID.randomUUID(), UUID.randomUUID(),
                TetherKind.CHAIN, UUID.randomUUID(), OVERWORLD, 1e9D, null, false);
        assertEquals(TetherRecord.MAX_LENGTH_BLOCKS, absurd.lengthBlocks());

        TetherRecord nan = TetherRecord.toHolder(UUID.randomUUID(), UUID.randomUUID(),
                TetherKind.CHAIN, UUID.randomUUID(), OVERWORLD, Double.NaN, null, false);
        assertTrue(Double.isFinite(nan.lengthBlocks()), "a non-finite length is an unbreakable tether");

        TetherRecord negative = TetherRecord.toHolder(UUID.randomUUID(), UUID.randomUUID(),
                TetherKind.CHAIN, UUID.randomUUID(), OVERWORLD, -5.0D, null, false);
        assertTrue(negative.lengthBlocks() > 0.0D);
    }

    @Test
    void aRowWithNoIdOrNoSubjectIsEmpty() {
        assertEquals(Optional.empty(), TetherRecord.load(null));
        assertEquals(Optional.empty(), TetherRecord.load(new CompoundTag()));

        CompoundTag idOnly = new CompoundTag();
        idOnly.putUUID("id", UUID.randomUUID());
        assertEquals(Optional.empty(), TetherRecord.load(idOnly));
    }

    @Test
    void aTetherHoldingNothingIsNotValid() {
        TetherRecord orphan = new TetherRecord(UUID.randomUUID(), UUID.randomUUID(), TetherKind.CHAIN,
                null, OVERWORLD, null, 4.0D, false, null, false, 1L);
        assertFalse(orphan.valid(), "a tether with neither end must not pin somebody to the origin");
    }

    @Test
    void anUnknownKindReadsAsALegacyHoldRatherThanThrowing() {
        assertEquals(TetherKind.LEGACY_HOLD, TetherKind.parse("grappling_hook"));
        assertEquals(TetherKind.LEGACY_HOLD, TetherKind.parse(null));
        assertEquals(TetherKind.ESCORT, TetherKind.parse("escort"));
    }
}
