package dev.otectus.mcacrime.tether;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every tether question is a map read, and none of them is a world scan (0.7.5 M4.1).
 *
 * <p>The source answers "what is anchored to this?" by iterating {@code server.getAllEntities()} on
 * <b>every right-click of any block</b> ({@code event/ModServerEvents.java:165-171}) — the single most
 * expensive thing a player can do by pressing a button on a populated server. These assertions pin the
 * replacement: three reverse indices maintained on write, each answering in one lookup, and each
 * staying correct across replacement and removal.
 */
class TetherIndexTest {

    private static final ResourceLocation OVERWORLD = ResourceLocation.fromNamespaceAndPath("minecraft", "overworld");
    private static final ResourceLocation NETHER = ResourceLocation.fromNamespaceAndPath("minecraft", "the_nether");

    private TetherIndex index;
    private UUID subject;
    private UUID holder;
    private BlockPos post;

    @BeforeEach
    void freshIndex() {
        index = new TetherIndex();
        subject = UUID.randomUUID();
        holder = UUID.randomUUID();
        post = new BlockPos(10, 64, -3);
    }

    private TetherRecord held() {
        return TetherRecord.toHolder(UUID.randomUUID(), subject, TetherKind.CHAIN, holder, OVERWORLD,
                5.0D, holder, true);
    }

    private TetherRecord anchored(UUID who, BlockPos at) {
        return new TetherRecord(UUID.randomUUID(), who, TetherKind.ANCHOR, UUID.randomUUID(), OVERWORLD,
                at, 5.0D, false, who, true, 1L);
    }

    // ---------------------------------------------------------------- the three lookups

    @Test
    void aSubjectIsFoundByTheirOwnIdWithoutWalkingTheTable() {
        TetherRecord tether = held();
        index.put(tether);
        List<TetherRecord> found = index.forSubject(subject);
        assertEquals(1, found.size());
        assertEquals(tether.id(), found.get(0).id());
        assertTrue(index.forSubject(UUID.randomUUID()).isEmpty(), "and a stranger finds nothing");
        assertTrue(index.forSubject(null).isEmpty());
    }

    @Test
    void aHolderIsFoundByTheirOwnId() {
        index.put(held());
        index.put(TetherRecord.toHolder(UUID.randomUUID(), UUID.randomUUID(), TetherKind.CHAIN, holder,
                OVERWORLD, 5.0D, holder, true));
        assertEquals(2, index.forHolder(holder).size());
        assertEquals(2, index.heldBy(holder), "which is also what the per-holder cap is read from");
        assertEquals(0, index.heldBy(UUID.randomUUID()));
    }

    @Test
    void aFencePostAnswersWhatIsTiedToItInOneLookup() {
        index.put(anchored(subject, post));
        index.put(anchored(UUID.randomUUID(), post));
        index.put(anchored(UUID.randomUUID(), new BlockPos(99, 64, 99)));

        assertEquals(2, index.forAnchor(OVERWORLD, post).size(),
                "two subjects on one knot, found without touching the third");
        assertTrue(index.anchored(OVERWORLD, post));
        assertFalse(index.anchored(OVERWORLD, new BlockPos(0, 0, 0)));
        assertFalse(index.anchored(OVERWORLD, null));
    }

    @Test
    void theSameCoordinatesInAnotherDimensionAreADifferentAnchor() {
        index.put(anchored(subject, post));
        assertTrue(index.anchored(OVERWORLD, post));
        assertFalse(index.anchored(NETHER, post),
                "a fence in the Nether is not the fence in the Overworld");
    }

    // ---------------------------------------------------------------- staying correct

    @Test
    void replacingATetherRekeysItRatherThanLeavingAGhost() {
        TetherRecord original = anchored(subject, post);
        index.put(original);
        BlockPos moved = new BlockPos(20, 64, 20);
        TetherRecord relocated = new TetherRecord(original.id(), subject, TetherKind.ANCHOR,
                original.holder(), OVERWORLD, moved, 5.0D, false, original.chainOwner(), true, 2L);
        index.put(relocated);

        assertFalse(index.anchored(OVERWORLD, post), "the old position is not still holding them");
        assertTrue(index.anchored(OVERWORLD, moved));
        assertEquals(1, index.size(), "and there is still exactly one tether");
    }

    @Test
    void removingATetherClearsItFromEveryIndex() {
        TetherRecord tether = held();
        index.put(tether);
        index.remove(tether.id());

        assertTrue(index.forSubject(subject).isEmpty());
        assertTrue(index.forHolder(holder).isEmpty());
        assertNull(index.get(tether.id()));
        assertEquals(0, index.size());
        index.remove(tether.id()); // idempotent
        index.remove(null);
        assertEquals(0, index.size());
    }

    @Test
    void rebuildingFromTheTableReplacesEverything() {
        index.put(held());
        TetherRecord only = anchored(UUID.randomUUID(), post);
        index.rebuild(List.of(only));

        assertEquals(1, index.size());
        assertTrue(index.forSubject(subject).isEmpty(), "the previous world's rows are gone");
        assertNotNull(index.get(only.id()));
        index.rebuild(null);
        assertEquals(0, index.size());
    }

    // ---------------------------------------------------------------- the holder chain

    @Test
    void theHolderOfASubjectSkipsSuspendedTethers() {
        TetherRecord suspended = held().suspended(true);
        index.put(suspended);
        assertNull(index.holderOf(subject),
                "a chain the pillory suspended is not what is holding them right now");

        index.put(suspended.suspended(false));
        assertEquals(holder, index.holderOf(subject));
    }

    @Test
    void aTetherWithNoHolderNamesNobody() {
        index.put(TetherRecord.toAnchor(UUID.randomUUID(), subject, TetherKind.ANCHOR, OVERWORLD, post,
                5.0D, null, false));
        assertNull(index.holderOf(subject), "a fixed point is not a holder");
    }
}
