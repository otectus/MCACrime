package dev.otectus.mcacrime;

import dev.otectus.mcacrime.jail.SafeCustodyDestination;
import dev.otectus.mcacrime.jail.SafeCustodyDestination.BlockProbe;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Where custody may put somebody, and every reason it may not (T38).
 *
 * <p>The old rule was "three blocks, two of them air", plus a fallback to one block above the anchor
 * whenever the chunk was not loaded. Both halves were wrong in the same direction: the fallback fired
 * exactly when the server knew least about the destination, and "air" says nothing about a campfire,
 * a cactus, powder snow, standing water or somebody else already standing there.
 *
 * <p>One test per check, each failing precisely one probe. That is the only way to know a rule is
 * still being asked rather than accidentally implied by another; a validator that quietly stopped
 * testing the world border would pass every test that only checked a valid spot.
 */
class SafeCustodyDestinationTest {

    private static final BlockPos ANCHOR = new BlockPos(8, 64, 8);

    /** A probe that answers yes to everything: the world as custody would like to find it. */
    private static BlockProbe everythingFine() {
        return (check, pos) -> true;
    }

    /** The same, refusing exactly one question everywhere. */
    private static BlockProbe refusing(BlockProbe.Check refused) {
        return (check, pos) -> check != refused;
    }

    @Test
    void aGoodSpotIsAccepted() {
        assertTrue(SafeCustodyDestination.isSafeStand(everythingFine(), ANCHOR));
        assertEquals(Optional.of(ANCHOR), SafeCustodyDestination.validate(everythingFine(), ANCHOR, 4));
    }

    @Test
    void anUnloadedPositionIsRefused() {
        assertFalse(SafeCustodyDestination.isSafeStand(refusing(BlockProbe.Check.LOADED), ANCHOR),
                "an unloaded destination is a guess, and guessing is what dropped prisoners into walls");
    }

    @Test
    void aBlockedPositionIsRefused() {
        assertFalse(SafeCustodyDestination.isSafeStand(refusing(BlockProbe.Check.PASSABLE), ANCHOR));
    }

    @Test
    void aPositionWithNothingToStandOnIsRefused() {
        assertFalse(SafeCustodyDestination.isSafeStand(refusing(BlockProbe.Check.SUPPORTED), ANCHOR));
    }

    @Test
    void aHazardousPositionIsRefused() {
        assertFalse(SafeCustodyDestination.isSafeStand(refusing(BlockProbe.Check.SAFE), ANCHOR),
                "fire, magma, cactus, powder snow and lava are all not air");
    }

    @Test
    void aPositionOutsideTheWorldBorderIsRefused() {
        assertFalse(SafeCustodyDestination.isSafeStand(refusing(BlockProbe.Check.IN_BORDER), ANCHOR));
    }

    @Test
    void aPositionSomebodyElseIsStandingInIsRefused() {
        assertFalse(SafeCustodyDestination.isSafeStand(refusing(BlockProbe.Check.UNOCCUPIED), ANCHOR));
    }

    @Test
    void theHeadIsCheckedAsThoroughlyAsTheFeet() {
        // A two-block gap whose upper block is lava is not a gap.
        BlockProbe headInLava = (check, pos) ->
                check != BlockProbe.Check.SAFE || !pos.equals(ANCHOR.above());

        assertFalse(SafeCustodyDestination.isSafeStand(headInLava, ANCHOR));
    }

    // ------------------------------------------------------------------ the search

    @Test
    void theSearchReturnsTheNearestValidSpot() {
        BlockPos wanted = ANCHOR.above(2);
        BlockProbe onlyAtWanted = (check, pos) ->
                pos.equals(wanted) || pos.equals(wanted.above()) || check == BlockProbe.Check.SUPPORTED;

        assertEquals(Optional.of(wanted), SafeCustodyDestination.validate(onlyAtWanted, ANCHOR, 8));
    }

    @Test
    void theSearchPrefersTheAnchorItself() {
        assertEquals(Optional.of(ANCHOR), SafeCustodyDestination.validate(everythingFine(), ANCHOR, 8),
                "a prisoner moved further than necessary is a prisoner outside the cell they were sent to");
    }

    @Test
    void nowhereSafeIsAnEmptyAnswerRatherThanAGuess() {
        assertTrue(SafeCustodyDestination.validate(refusing(BlockProbe.Check.SUPPORTED), ANCHOR, 8)
                .isEmpty(), "the old code fell back to anchor.above() here, which is how people drowned");
    }

    @Test
    void aZeroRadiusSearchLooksOnlyAtTheAnchor() {
        BlockProbe onlyAbove = (check, pos) -> pos.getY() >= ANCHOR.getY() + 1;

        assertTrue(SafeCustodyDestination.validate(onlyAbove, ANCHOR, 0).isEmpty());
    }
}
