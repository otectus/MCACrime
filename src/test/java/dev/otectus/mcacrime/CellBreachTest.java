package dev.otectus.mcacrime;

import dev.otectus.mcacrime.jail.CellBlueprint;
import dev.otectus.mcacrime.jail.HoldingCell;
import dev.otectus.mcacrime.jail.JailConfine;
import dev.otectus.mcacrime.jail.JailContainmentMode;
import dev.otectus.mcacrime.jail.JailState;
import dev.otectus.mcacrime.locks.LockRecord;
import dev.otectus.mcacrime.locks.LockService;
import dev.otectus.mcacrime.locks.LockTarget;
import dev.otectus.mcacrime.state.world.CrimeWorldData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * When leaving a built cell is an escape, and how a cell knows its door has been breached.
 *
 * <p>The door is the one intended way out of a generated cell, and the padlock on it is the only
 * thing holding it. Whether that lock still holds is read from the {@code locks} table rather than
 * stored on the cell, so the answer can never lag behind a pick: the tests below pin each state of
 * the row to the answer the confinement tick will get.
 *
 * <p>Block states are null throughout, as in {@code HoldingCellJournalTest}: nothing here reads one.
 */
class CellBreachTest {

    private static final UUID PRISONER = UUID.fromString("00000000-0000-0000-0000-0000000000a2");
    private static final UUID SENTENCE = UUID.fromString("00000000-0000-0000-0000-0000000000b2");
    private static final UUID PADLOCK = UUID.fromString("00000000-0000-0000-0000-0000000000c2");
    private static final ResourceLocation OVERWORLD = new ResourceLocation("minecraft", "overworld");
    private static final BlockPos ANCHOR = new BlockPos(16, 64, 16);
    private static final BlockPos DOOR = ANCHOR.offset(CellBlueprint.RADIUS, 0, 0);

    private static HoldingCell bare() {
        return new HoldingCell(PRISONER, SENTENCE, ANCHOR, OVERWORLD, CellBlueprint.RADIUS, 0L, Map.of(), Map.of());
    }

    private static LockRecord lockedDoor(CrimeWorldData data) {
        return LockService.create(data, LockTarget.block(OVERWORLD, DOOR), null).orElseThrow();
    }

    // ------------------------------------------------------------------ the rule

    @Test
    void physicalModeIsAlwaysAnEscapeAndABreachedDoorIsOneInEveryMode() {
        for (JailContainmentMode mode : JailContainmentMode.values()) {
            assertTrue(JailConfine.exitIsEscape(mode, true), "breached, " + mode);
        }
        assertTrue(JailConfine.exitIsEscape(JailContainmentMode.PHYSICAL, false));
        assertFalse(JailConfine.exitIsEscape(JailContainmentMode.CONTAINMENT, false),
                "an intact door in CONTAINMENT still teleports the prisoner back");
        assertFalse(JailConfine.exitIsEscape(JailContainmentMode.REINFORCED, false));
        assertFalse(JailConfine.exitIsEscape(null, false));
    }

    // ------------------------------------------------------------------ breach

    @Test
    void aCellWithoutADoorIsNeverBreached() {
        CrimeWorldData data = new CrimeWorldData();
        assertFalse(bare().breached(data));
        assertFalse(bare().hasDoor());
    }

    @Test
    void aLockedPadlockOnTheDoorIsNotABreach() {
        CrimeWorldData data = new CrimeWorldData();
        LockRecord lock = lockedDoor(data);
        HoldingCell cell = bare().withDoor(DOOR, Direction.EAST, lock.lockId(), PADLOCK);

        assertTrue(cell.hasDoor());
        assertFalse(cell.breached(data));
    }

    @Test
    void aPickedPadlockDetachesItsLockWhichIsABreach() {
        CrimeWorldData data = new CrimeWorldData();
        LockRecord lock = lockedDoor(data);
        HoldingCell cell = bare().withDoor(DOOR, Direction.EAST, lock.lockId(), PADLOCK);

        assertTrue(LockService.detach(data, lock.lockId()), "pickedOpen detaches the padlock's lock");

        assertTrue(cell.breached(data));
    }

    @Test
    void aLockTurnedOpenWithAKeyIsABreach() {
        CrimeWorldData data = new CrimeWorldData();
        LockRecord lock = lockedDoor(data);
        HoldingCell cell = bare().withDoor(DOOR, Direction.EAST, lock.lockId(), PADLOCK);

        LockService.setLocked(data, lock.lockId(), false);

        assertTrue(cell.breached(data));
    }

    @Test
    void aForgottenLockRowIsABreach() {
        CrimeWorldData data = new CrimeWorldData();
        LockRecord lock = lockedDoor(data);
        HoldingCell cell = bare().withDoor(DOOR, Direction.EAST, lock.lockId(), PADLOCK);

        LockService.forget(data, lock.lockId());

        assertTrue(cell.breached(data));
        assertTrue(cell.breached(null), "no store at all is not a lock that holds");
    }

    // ------------------------------------------------------------------ the record

    @Test
    void theDoorSurvivesNarrowingAndTheRoundTrip() {
        HoldingCell cell = bare().withDoor(DOOR, Direction.SOUTH, SENTENCE, PADLOCK);
        HoldingCell narrowed = cell.retaining(Set.of());
        assertEquals(DOOR, narrowed.door());
        assertEquals(Direction.SOUTH, narrowed.facing());
        assertEquals(SENTENCE, narrowed.lockId());
        assertEquals(PADLOCK, narrowed.padlock());

        HoldingCell loaded = HoldingCell.load(cell.save());
        assertEquals(DOOR, loaded.door());
        assertEquals(Direction.SOUTH, loaded.facing());
        assertEquals(SENTENCE, loaded.lockId());
        assertEquals(PADLOCK, loaded.padlock());
        assertTrue(loaded.hasDoor());
    }

    @Test
    void aCellSavedBeforeDoorsExistedLoadsWithoutOne() {
        HoldingCell loaded = HoldingCell.load(bare().save());
        assertNull(loaded.door());
        assertNull(loaded.facing());
        assertNull(loaded.lockId());
        assertNull(loaded.padlock());
        assertFalse(loaded.hasDoor());
    }

    /** A released prisoner is stood outside the door, and an old cell keeps its old side. */
    @Test
    void theOutsideStandIsOnTheDoorSide() {
        int reach = CellBlueprint.RADIUS + 2;
        assertEquals(ANCHOR.offset(0, 0, -reach),
                bare().withDoor(DOOR, Direction.NORTH, SENTENCE, PADLOCK).outsideStand());
        assertEquals(ANCHOR.offset(-reach, 0, 0),
                bare().withDoor(DOOR, Direction.WEST, SENTENCE, PADLOCK).outsideStand());
        assertEquals(ANCHOR.offset(reach, 0, 0), bare().outsideStand(),
                "a cell with no door releases where it always did");
        assertEquals(Direction.EAST, bare().withDoor(DOOR, Direction.DOWN, SENTENCE, PADLOCK).facing(),
                "a vertical facing is corrected rather than stored");
    }

    // ------------------------------------------------------------------ the sentence

    @Test
    void theTemporaryCellFlagRoundTripsAndDefaultsToFalse() {
        JailState state = new JailState(600L, ANCHOR, OVERWORLD, 2, JailContainmentMode.CONTAINMENT);
        assertFalse(state.isTemporaryCell());
        state.setTemporaryCell(true);
        assertTrue(JailState.load(state.save()).isTemporaryCell());
        assertTrue(state.copy().isTemporaryCell());

        JailState legacy = JailState.load(new JailState(600L, ANCHOR, OVERWORLD, 2,
                JailContainmentMode.PHYSICAL).save());
        assertFalse(legacy.isTemporaryCell(), "a sentence saved without the flag keeps the old rule");
    }

    @Test
    void reanchoringMovesOnlyThePlace() {
        JailState state = new JailState(600L, ANCHOR, OVERWORLD, 2, JailContainmentMode.CONTAINMENT);
        UUID id = state.getSentenceId();
        state.setRealOnlineTicksServed(40L);
        state.setSurrenderCredited(true);
        state.setEscaped(true);

        BlockPos elsewhere = new BlockPos(-30, 70, 5);
        state.reanchor(elsewhere, OVERWORLD, 3, true);

        assertEquals(elsewhere, state.getJailAnchor());
        assertEquals(3, state.getJailRadius());
        assertTrue(state.isTemporaryCell());
        assertEquals(id, state.getSentenceId());
        assertEquals(600L, state.getRemainingOnlineTicks());
        assertEquals(40L, state.getRealOnlineTicksServed());
        assertTrue(state.isSurrenderCredited());
        assertTrue(state.isEscaped(), "reanchor is the move; JailService clears the flag once the move succeeded");
    }
}
