package dev.otectus.mcacrime;

import dev.otectus.mcacrime.state.world.AccompliceRecord;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The one piece of the accomplice system that outlives the session.
 *
 * <p>The arrest counter is the reason this record is persisted at all rather than living in memory
 * beside the effects: it is what makes bailing the same relative out a second time cost more, and a
 * counter a restart resets is a discount for restarting.
 */
class AccompliceRecordTest {

    private static final UUID VILLAGER = UUID.nameUUIDFromBytes("villager".getBytes());
    private static final UUID PLAYER = UUID.nameUUIDFromBytes("player".getBytes());

    private static AccompliceRecord sample() {
        return new AccompliceRecord(VILLAGER, PLAYER, "lookout", "incident-1", 100L, 2500L, false, 3,
                1800L, 2);
    }

    @Test
    void everyFieldSurvivesTheRoundTrip() {
        AccompliceRecord loaded = AccompliceRecord.load(sample().save());
        assertEquals(sample(), loaded);
    }

    @Test
    void aRecordWithNoIncidentIdRoundTripsAsBlankRatherThanNull() {
        AccompliceRecord bare = new AccompliceRecord(VILLAGER, PLAYER, "lookout", null, 0L, 10L, false,
                0, 0L, 0);
        AccompliceRecord loaded = AccompliceRecord.load(bare.save());
        assertEquals("", loaded.incidentId());
        assertEquals(bare, loaded);
    }

    @Test
    void aTagWrittenBeforeTheCounterExistedReadsAsZeroArrests() {
        CompoundTag tag = sample().save();
        tag.remove("priorArrests");
        assertEquals(0, AccompliceRecord.load(tag).priorArrests());
    }

    @Test
    void expiryIsExclusiveAtTheExpiryTick() {
        AccompliceRecord record = sample();
        assertTrue(record.active(2499L));
        assertFalse(record.active(2500L));
        assertFalse(record.active(2501L));
    }

    @Test
    void beingSeenMakesThemWantedAndDoesNotDoItTwice() {
        AccompliceRecord wanted = sample().asWanted();
        assertTrue(wanted.wanted());
        assertSameInstanceWhenAlreadyWanted(wanted);
        // Nothing else about the agreement changes when the warrant goes out.
        assertEquals(sample().timesAssisted(), wanted.timesAssisted());
        assertEquals(sample().priorArrests(), wanted.priorArrests());
    }

    private static void assertSameInstanceWhenAlreadyWanted(AccompliceRecord wanted) {
        assertEquals(wanted, wanted.asWanted());
    }

    @Test
    void arrestSpendsTheWarrantAndCountsTheArrest() {
        AccompliceRecord arrested = sample().asWanted().arrested();
        assertFalse(arrested.wanted(), "a served warrant must not leave them wanted for the same help");
        assertEquals(3, arrested.priorArrests());
    }

    @Test
    void renewalKeepsTheHistoryAndMovesTheClock() {
        AccompliceRecord renewed = sample().asWanted().renewed("distraction", "incident-2", 5000L, 5600L);
        assertEquals("distraction", renewed.role());
        assertEquals(5000L, renewed.startedAtTick());
        assertEquals(5600L, renewed.expiresAtTick());
        assertEquals(5000L, renewed.lastAssistedTick());
        assertEquals(4, renewed.timesAssisted());
        assertEquals(2, renewed.priorArrests());
        assertTrue(renewed.wanted(), "a second job must not launder a standing warrant");
        assertNotSame(sample(), renewed);
    }
}
