package dev.otectus.mcacrime;

import dev.otectus.mcacrime.economy.fence.FenceStockRecord;
import dev.otectus.mcacrime.state.world.CrimeWorldData;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A fence's stock is a fact about the fence, not about the screen (0.6.0, T14/T15, audit finding B07).
 *
 * <p>The bug this closes was worth a lot of emeralds: uses lived on the {@code MerchantOffer} objects
 * built when the screen opened, so closing and reopening handed the player a fresh eight of
 * everything and a fence's "limited" stock was limited only by how fast the player could click. The
 * counts live in world data now, and these are the four things that has to mean — reopening sees what
 * is left, an exhausted trade refuses, a restock resets it, and a screen built before the restock
 * cannot spend what came after it.
 *
 * <p>No server and no merchant screen: the record and {@link CrimeWorldData} are the whole mechanism,
 * and a running game would only make the argument harder to read.
 */
class FenceStockTest {

    private static final UUID FENCE = UUID.fromString("00000000-0000-0000-0000-0000000000f1");
    private static final ResourceLocation LOCKPICK = ResourceLocation.fromNamespaceAndPath("mcacrime", "lockpick");
    private static final int MAX_USES = 8;

    private static String offer() {
        return FenceStockRecord.offerId(LOCKPICK, false);
    }

    // ------------------------------------------------------------------ T14: reopening

    @Test
    void reopeningTheSameFenceSeesTheUsesAlreadySpent() {
        CrimeWorldData data = new CrimeWorldData();
        FenceStockRecord opened = new FenceStockRecord(FENCE, 1, 24000L);
        assertTrue(data.putFenceStock(opened).stored());
        assertTrue(opened.tryConsume(offer(), MAX_USES));
        assertTrue(opened.tryConsume(offer(), MAX_USES));
        data.putFenceStock(opened);

        // The screen closes and opens again: same fence, same world data, same stocking.
        FenceStockRecord reopened = data.getFenceStock(FENCE);
        assertNotNull(reopened);
        assertEquals(2, reopened.uses(offer()));
        assertEquals(MAX_USES - 2, reopened.remaining(offer(), MAX_USES),
                "closing the screen must not restock the fence");
    }

    @Test
    void usesSurviveBeingWrittenToDiskAndReadBack() {
        FenceStockRecord record = new FenceStockRecord(FENCE, 3, 48000L);
        record.tryConsume(offer(), MAX_USES);
        record.tryConsume(FenceStockRecord.offerId(LOCKPICK, true), MAX_USES);

        CompoundTag tag = record.save();
        FenceStockRecord loaded = FenceStockRecord.load(tag);

        assertEquals(FENCE, loaded.fence());
        assertEquals(3, loaded.epoch());
        assertEquals(48000L, loaded.nextRestockTick());
        assertEquals(1, loaded.uses(offer()));
        assertEquals(1, loaded.uses(FenceStockRecord.offerId(LOCKPICK, true)),
                "the two directions are separate trades with separate stock");
    }

    // ------------------------------------------------------------------ T15: exhaustion and restock

    @Test
    void aTradeAtTheLimitIsRefusedAndDecrementsNothing() {
        FenceStockRecord record = new FenceStockRecord(FENCE, 1, 24000L);
        for (int i = 0; i < MAX_USES; i++) {
            assertTrue(record.tryConsume(offer(), MAX_USES), "use " + i + " should have been available");
        }
        assertFalse(record.tryConsume(offer(), MAX_USES), "the ninth of eight must be refused");
        assertEquals(MAX_USES, record.uses(offer()), "a refused trade changes nothing");
        assertEquals(0, record.remaining(offer(), MAX_USES));
    }

    @Test
    void restockingResetsTheUsesAndBumpsTheEpoch() {
        FenceStockRecord record = new FenceStockRecord(FENCE, 1, 24000L);
        record.tryConsume(offer(), MAX_USES);
        assertFalse(record.dueForRestock(23999L), "not yet");
        assertTrue(record.dueForRestock(24000L));

        record.restock(48000L);

        assertEquals(2, record.epoch());
        assertEquals(0, record.uses(offer()), "new goods on the counter");
        assertEquals(48000L, record.nextRestockTick());
        assertFalse(record.dueForRestock(24000L));
    }

    @Test
    void aMenuFromTheStockingBeforeTheRestockIsStale() {
        FenceStockRecord record = new FenceStockRecord(FENCE, 1, 24000L);
        int openMenuEpoch = record.epoch();
        assertTrue(record.isCurrent(openMenuEpoch));

        record.restock(48000L);

        assertFalse(record.isCurrent(openMenuEpoch),
                "a screen left open across a restock is looking at goods that are gone");
        assertTrue(record.isCurrent(record.epoch()));
    }

    @Test
    void aDisabledScheduleNeverFallsDue() {
        FenceStockRecord record = new FenceStockRecord(FENCE, 1, FenceStockRecord.DISABLED);
        assertFalse(record.dueForRestock(Long.MAX_VALUE));
    }
}
