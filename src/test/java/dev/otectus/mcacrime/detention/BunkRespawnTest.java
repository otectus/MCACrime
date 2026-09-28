package dev.otectus.mcacrime.detention;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A bunk gives a respawn point back only while it still owns the override (0.7.5 M4.7).
 *
 * <p>The acceptance test of specification §21.3's {@code releaseAfterPlayerChoseAnotherSpawn}: a
 * prisoner who slept in a bunk and has since slept in their own bed keeps the bed. Ownership is one
 * comparison — is the player's respawn still the bunk this system set? — rather than a flag the mod
 * sets and then trusts, because a flag cannot tell "nobody has touched it" from "somebody has chosen
 * something else since".
 */
class BunkRespawnTest {

    private static final BlockPos BUNK = new BlockPos(8, 64, 8);
    private static final BlockPos OWN_BED = new BlockPos(-40, 70, 12);

    private static BunkRespawnPolicy.Snapshot snapshot(BlockPos previous) {
        return new BunkRespawnPolicy.Snapshot("minecraft:overworld", previous, 90.0F, false, BUNK);
    }

    @Test
    void anUntouchedBunkSpawnIsGivenBackOnRelease() {
        assertTrue(BunkRespawnPolicy.restores(BUNK, "minecraft:overworld", snapshot(OWN_BED)),
                "the player still respawns at the bunk, so the override is still ours to undo");
    }

    @Test
    void releaseAfterPlayerChoseAnotherSpawnKeepsTheNewerOne() {
        assertFalse(BunkRespawnPolicy.restores(OWN_BED, "minecraft:overworld", snapshot(OWN_BED)),
                "they slept in their own bed since; that is a decision, not a leftover");
        assertFalse(BunkRespawnPolicy.restores(new BlockPos(500, 64, 500), "minecraft:overworld",
                snapshot(OWN_BED)), "a respawn anchor, another mod's spawn point, anything newer");
    }

    @Test
    void aClearedSpawnIsNotOverwrittenEither() {
        assertFalse(BunkRespawnPolicy.restores(null, "minecraft:overworld", snapshot(OWN_BED)),
                "no respawn point at all is also a state this mod has no business rewriting");
    }

    @Test
    void aPlayerWhoHadNoSpawnBeforeTheBunkStillGetsThatBack() {
        assertTrue(BunkRespawnPolicy.restores(BUNK, "minecraft:overworld", snapshot(null)),
                "having had none is a previous state like any other");
    }

    @Test
    void withoutASnapshotNothingIsRestored() {
        assertFalse(BunkRespawnPolicy.restores(BUNK, "minecraft:overworld", null),
                "a restart loses the snapshot, and the safe direction is to leave the bunk spawn");
    }

    @Test
    void theSnapshotBoundsItsOwnFields() {
        BunkRespawnPolicy.Snapshot bad = new BunkRespawnPolicy.Snapshot(null, null, Float.NaN, true, null);
        assertTrue(Float.isFinite(bad.angle()), "a non-finite angle never reaches a respawn call");
        assertTrue(BlockPos.ZERO.equals(bad.bunkPos()));
    }

    @Test
    void forgettingASnapshotLeavesNothingToApplyLater() {
        java.util.UUID player = java.util.UUID.randomUUID();
        BunkRespawnPolicy.forget(player);
        assertTrue(BunkRespawnPolicy.snapshot(player).isEmpty());
        assertTrue(BunkRespawnPolicy.snapshot(null).isEmpty());
        BunkRespawnPolicy.clearAll();
    }
}
