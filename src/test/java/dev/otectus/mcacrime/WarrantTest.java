package dev.otectus.mcacrime;

import dev.otectus.mcacrime.ledger.Warrant;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The warrant, whose only job is to be an identity a bounty claim can be keyed on.
 *
 * <p>The revision is what makes the key unforgeable: die, respawn, offend again, and the key you were
 * already paid for stays claimed. So the revision bump is asserted directly, and so is the cap on the
 * linked-record list, because a career criminal's warrant is written on every autosave.
 */
class WarrantTest {

    private static final UUID OFFENDER = UUID.fromString("00000000-0000-0000-0000-0000000000aa");
    private static final ResourceLocation ASSAULT = ResourceLocation.fromNamespaceAndPath("mcacrime", "assault");
    private static final ResourceLocation MURDER = ResourceLocation.fromNamespaceAndPath("mcacrime", "murder");

    private static Warrant opened() {
        return Warrant.open(UUID.randomUUID(), OFFENDER, ASSAULT, UUID.randomUUID(), 100L);
    }

    @Test
    void openStartsAtRevisionOneAndIsOpen() {
        Warrant warrant = opened();
        assertEquals(1L, warrant.revision());
        assertTrue(warrant.open());
        assertEquals(1, warrant.recordIds().size());
    }

    @Test
    void revisedBumpsTheRevisionAndTheTopOffense() {
        Warrant warrant = opened().revised(UUID.randomUUID(), MURDER, 200L);
        assertEquals(2L, warrant.revision());
        assertEquals(MURDER, warrant.topOffense());
        assertEquals(200L, warrant.lastRevisedAt());
        assertEquals(2, warrant.recordIds().size());
    }

    @Test
    void revisingWithTheSameRecordDoesNotDuplicateTheId() {
        UUID recordId = UUID.randomUUID();
        Warrant warrant = Warrant.open(UUID.randomUUID(), OFFENDER, ASSAULT, recordId, 100L)
                .revised(recordId, ASSAULT, 200L);
        assertEquals(1, warrant.recordIds().size());
        assertEquals(2L, warrant.revision(), "the offence still happened even if the id repeated");
    }

    @Test
    void recordIdsAreCappedAndTheOldestGoFirst() {
        Warrant warrant = opened();
        UUID last = null;
        for (int i = 0; i < Warrant.MAX_RECORD_IDS + 10; i++) {
            last = UUID.randomUUID();
            warrant = warrant.revised(last, ASSAULT, 300L + i);
        }
        assertEquals(Warrant.MAX_RECORD_IDS, warrant.recordIds().size());
        assertEquals(last, warrant.recordIds().get(warrant.recordIds().size() - 1));
    }

    @Test
    void closedFlipsOpenAndKeepsTheRevision() {
        Warrant warrant = opened().revised(UUID.randomUUID(), MURDER, 200L).closed(500L);
        assertFalse(warrant.open());
        assertEquals(500L, warrant.closedAt());
        assertEquals(2L, warrant.revision(), "a closed warrant still has to resolve an old claim key");
    }

    @Test
    void nbtRoundTripsEveryField() {
        Warrant warrant = opened().revised(UUID.randomUUID(), MURDER, 200L).closed(500L);
        CompoundTag tag = warrant.save();
        assertEquals(warrant, Warrant.load(tag));
    }

    @Test
    void aWarrantWithNoOffenseIdStillLoads() {
        Warrant warrant = Warrant.open(UUID.randomUUID(), OFFENDER, null, null, 100L);
        Warrant loaded = Warrant.load(warrant.save());
        assertEquals(warrant, loaded);
        assertTrue(loaded.recordIds().isEmpty());
    }
}
