package dev.otectus.mcacrime;

import dev.otectus.mcacrime.enforcement.ArrestPhase;
import dev.otectus.mcacrime.enforcement.ArrestState;
import dev.otectus.mcacrime.jail.JailAnchor;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The arrest record round-trips, and survives everything a save file can do to it.
 *
 * <p>This is the file that makes "reconnecting cannot trivially bypass the sentence" true: an arrest
 * that lives only in a static map is erased by a restart, and one that lives here is not.
 */
class ArrestStateNbtTest {

    private static final ResourceLocation OVERWORLD = new ResourceLocation("minecraft", "overworld");

    @Test
    void roundTripsEverythingThatOutlivesARestart() {
        UUID guard = UUID.randomUUID();
        UUID encounter = UUID.randomUUID();
        ArrestState state = new ArrestState();
        state.setGuard(guard);
        state.setEncounterId(encounter);
        state.setAnchor(new JailAnchor(new BlockPos(12, 65, -30), OVERWORLD, 8));
        state.setSentenceTicks(2400L);
        state.setDeadlineOnlineTick(9_000L);
        state.setLastSeenPos(new BlockPos(1, 2, 3));

        ArrestState back = ArrestState.load(state.save());

        assertEquals(guard, back.getGuard());
        assertEquals(encounter, back.getEncounterId());
        assertEquals(2400L, back.getSentenceTicks());
        assertEquals(9_000L, back.getDeadlineOnlineTick());
        assertEquals(state.getSentenceId(), back.getSentenceId());
        assertEquals(new BlockPos(1, 2, 3), back.getLastSeenPos());
        JailAnchor anchor = back.anchor();
        assertNotNull(anchor);
        assertEquals(new BlockPos(12, 65, -30), anchor.pos());
        assertEquals(OVERWORLD, anchor.dim());
        assertEquals(8, anchor.radius());
    }

    @Test
    void anEmptyTagLoadsAsNoArrestAndNeverThrows() {
        ArrestState back = ArrestState.load(new CompoundTag());
        assertEquals(ArrestPhase.NONE, back.getPhase());
        assertNull(back.getGuard());
        assertNull(back.getEncounterId());
        assertNull(back.anchor());
        assertNull(back.getLastSeenPos());
        assertEquals(0L, back.getSentenceTicks());
        assertNotNull(back.getSentenceId(), "a fresh id is minted rather than left null");
    }

    /** A hand-edited or newer save must degrade to "no arrest", never crash the login. */
    @Test
    void anUnknownPhaseNameDegradesToNone() {
        CompoundTag tag = new CompoundTag();
        tag.putString("phase", "AWAITING_TRIAL");
        assertEquals(ArrestPhase.NONE, ArrestState.load(tag).getPhase());
    }

    /** A half-written anchor is no anchor, so the reconcile path routes to recovery rather than nowhere. */
    @Test
    void aPartialAnchorResolvesToNothing() {
        CompoundTag tag = new CompoundTag();
        tag.putInt("ax", 4);
        tag.putInt("ay", 64);
        tag.putInt("az", 4);
        assertNull(ArrestState.load(tag).anchor(), "coordinates with no dimension are not a destination");

        CompoundTag dimOnly = new CompoundTag();
        dimOnly.putString("adim", OVERWORLD.toString());
        assertNull(ArrestState.load(dimOnly).anchor());
    }

    @Test
    void aMalformedDimensionIdIsDroppedRatherThanThrown() {
        CompoundTag tag = new CompoundTag();
        tag.putInt("ax", 0);
        tag.putInt("ay", 64);
        tag.putInt("az", 0);
        tag.putString("adim", "NOT A DIMENSION");
        assertNull(ArrestState.load(tag).anchor());
    }

    @Test
    void copyIsDeepEnoughThatDeathDoesNotShareState() {
        ArrestState state = new ArrestState();
        state.setGuard(UUID.randomUUID());
        state.setSentenceTicks(100L);
        state.setStuckStrikes(3);

        ArrestState copy = state.copy();
        copy.setSentenceTicks(999L);
        copy.setStuckStrikes(0);

        assertEquals(100L, state.getSentenceTicks());
        assertEquals(3, state.getStuckStrikes());
        assertEquals(state.getSentenceId(), copy.getSentenceId(), "the sentence is the same sentence");
    }

    @Test
    void theDeadlineRunsOnTheOnlineClockAndZeroMeansNever() {
        ArrestState state = new ArrestState();
        assertFalse(state.expired(Long.MAX_VALUE), "an unarmed arrest has no deadline to miss");
        state.setDeadlineOnlineTick(500L);
        assertFalse(state.expired(499L));
        assertTrue(state.expired(500L));
        assertTrue(state.expired(501L));
    }

    @Test
    void negativeValuesAreClampedRatherThanStored() {
        ArrestState state = new ArrestState();
        state.setSentenceTicks(-5L);
        state.setDeadlineOnlineTick(-5L);
        state.setStuckStrikes(-5);
        assertEquals(0L, state.getSentenceTicks());
        assertEquals(0L, state.getDeadlineOnlineTick());
        assertEquals(0, state.getStuckStrikes());
    }
}
