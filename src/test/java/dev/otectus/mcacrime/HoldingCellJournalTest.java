package dev.otectus.mcacrime;

import dev.otectus.mcacrime.captivity.CustodyOwner;
import dev.otectus.mcacrime.captivity.CustodyRecord;
import dev.otectus.mcacrime.captivity.RestraintType;
import dev.otectus.mcacrime.jail.HoldingCell;
import dev.otectus.mcacrime.jail.HoldingCellService;
import dev.otectus.mcacrime.state.world.CrimeWorldData;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Cells that could not be fully taken apart, and prisoners still standing in them (T36, T37).
 *
 * <p>Dismantling used to drop the roster record first and demolish afterwards, which was exactly
 * backwards. Demolition skips positions in unloaded chunks — the normal case, because a prisoner is
 * released on login from wherever they logged out — so the record went and iron bars stayed, with
 * nothing anywhere that knew they were this mod's to remove. The first tests pin the journal that
 * fixes it: what could not be restored is written down, and a later retry clears it.
 *
 * <p>The occupancy tests are the other half of the same bug. Restoring a cell puts the floor and roof
 * courses back through the space a prisoner occupies, so an expired cell that somebody is still serving
 * in has to go through the release path. The decision is asserted here; the sweep that acts on it needs
 * a live server and is not reachable from a unit test.
 *
 * <p>Block states are null throughout, and deliberately: nothing in the journal looks at a state, it
 * only ever asks which <em>positions</em> are still standing, so a null one is the cheapest way to say
 * that the answer must not depend on it.
 */
class HoldingCellJournalTest {

    private static final UUID PRISONER = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final UUID SENTENCE = UUID.fromString("00000000-0000-0000-0000-0000000000b1");
    private static final ResourceLocation OVERWORLD =
            ResourceLocation.fromNamespaceAndPath("minecraft", "overworld");
    private static final BlockPos ANCHOR = new BlockPos(16, 64, 16);
    private static final BlockPos REACHABLE = new BlockPos(16, 64, 17);
    private static final BlockPos STRANDED = new BlockPos(16, 64, 18);

    private static HoldingCell cell() {
        Map<BlockPos, BlockState> replaced = new HashMap<>();
        replaced.put(REACHABLE, null);
        replaced.put(STRANDED, null);
        return new HoldingCell(PRISONER, SENTENCE, ANCHOR, OVERWORLD, 3, 0L, replaced, Map.of());
    }

    // ------------------------------------------------------------------ T36

    @Test
    void anUnreachableBlockKeepsTheCellInTheJournal() {
        CrimeWorldData data = new CrimeWorldData();
        data.putHoldingCell(cell());

        HoldingCellService.dismantle(data, PRISONER, c -> Set.of(STRANDED));

        assertNull(data.holdingCellFor(PRISONER), "the sentence is over; the roster entry should go");
        List<HoldingCell> pending = data.pendingCellRestorations();
        assertEquals(1, pending.size(), "a cell half-standing in the world was forgotten entirely");
        assertEquals(Set.of(STRANDED), pending.get(0).replaced().keySet(),
                "the journal should carry exactly what is still standing, not the whole cell");
        assertEquals(SENTENCE, pending.get(0).sentenceId());
    }

    @Test
    void aFullyRestoredCellLeavesNothingBehind() {
        CrimeWorldData data = new CrimeWorldData();
        data.putHoldingCell(cell());

        HoldingCellService.dismantle(data, PRISONER, c -> Set.of());

        assertNull(data.holdingCellFor(PRISONER));
        assertTrue(data.pendingCellRestorations().isEmpty());
    }

    @Test
    void aLaterRetryClearsTheJournal() {
        CrimeWorldData data = new CrimeWorldData();
        data.putHoldingCell(cell());
        HoldingCellService.dismantle(data, PRISONER, c -> Set.of(STRANDED));

        assertTrue(HoldingCellService.retryPending(data, PRISONER, c -> Set.of()));
        assertTrue(data.pendingCellRestorations().isEmpty(), "the retry succeeded and the entry stayed");
    }

    @Test
    void aRetryThatStillCannotReachItKeepsWaiting() {
        CrimeWorldData data = new CrimeWorldData();
        data.putHoldingCell(cell());
        HoldingCellService.dismantle(data, PRISONER, c -> Set.of(REACHABLE, STRANDED));

        assertFalse(HoldingCellService.retryPending(data, PRISONER, c -> Set.of(STRANDED)));
        List<HoldingCell> pending = data.pendingCellRestorations();
        assertEquals(1, pending.size());
        assertEquals(Set.of(STRANDED), pending.get(0).replaced().keySet(),
                "a partial retry should shrink the journal rather than leave it or close it");
    }

    @Test
    void retryingNothingIsHarmless() {
        CrimeWorldData data = new CrimeWorldData();
        assertFalse(HoldingCellService.retryPending(data, PRISONER, c -> Set.of(STRANDED)));
        assertTrue(data.pendingCellRestorations().isEmpty());
    }

    // ------------------------------------------------------------------ T37

    @Test
    void aCellIsOccupiedWhileItsOwnSentenceIsBeingServed() {
        CrimeWorldData data = new CrimeWorldData();
        assertTrue(HoldingCellService.occupied(data, cell(), SENTENCE),
                "expiry would have restored the roof through the prisoner standing under it");
    }

    @Test
    void aCellIsNotOccupiedByAPrisonerServingSomethingElse() {
        CrimeWorldData data = new CrimeWorldData();
        assertFalse(HoldingCellService.occupied(data, cell(), UUID.randomUUID()),
                "a cell left over from a previous sentence is an empty cage");
    }

    @Test
    void anOfflinePrisonerStillInCustodyCountsAsOccupying() {
        CrimeWorldData data = new CrimeWorldData();
        data.putCustody(new CustodyRecord(PRISONER, true, true, CustodyOwner.guard(UUID.randomUUID()),
                RestraintType.NONE, 0L, ANCHOR, OVERWORLD));

        assertTrue(HoldingCellService.occupied(data, cell(), null),
                "an offline prisoner has no sentence to read, so custody is what says they are in there");
    }

    @Test
    void anOfflinePrisonerNobodyHoldsIsNotOccupying() {
        CrimeWorldData data = new CrimeWorldData();
        assertFalse(HoldingCellService.occupied(data, cell(), null));
    }

    @Test
    void aPre040CellWithNoSentenceIdIsGivenTheBenefitOfTheDoubt() {
        CrimeWorldData data = new CrimeWorldData();
        HoldingCell legacy = new HoldingCell(PRISONER, null, ANCHOR, OVERWORLD, 3, 0L, Map.of(), Map.of());

        assertTrue(HoldingCellService.occupied(data, legacy, UUID.randomUUID()),
                "being wrong here costs an unnecessary release; being wrong the other way suffocates");
    }

    @Test
    void narrowingACellKeepsItsIdentity() {
        HoldingCell narrowed = cell().retaining(Set.of(STRANDED));

        assertEquals(PRISONER, narrowed.prisoner());
        assertEquals(ANCHOR, narrowed.anchor());
        assertEquals(3, narrowed.radius());
        assertNotNull(narrowed.dim());
        assertEquals(Set.of(STRANDED), narrowed.replaced().keySet());
    }
}
