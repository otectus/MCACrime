package dev.otectus.mcacrime.detention;

import dev.otectus.mcacrime.state.PlayerCrimeData;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class BunkSnapshotPersistenceTest {
    @Test
    void snapshotSurvivesPlayerSaveAndCloneAndOwnsOnlyItsDimension() {
        BlockPos bunk = new BlockPos(10, 65, 10);
        BunkRespawnPolicy.Snapshot snapshot = new BunkRespawnPolicy.Snapshot(
                "minecraft:overworld", new BlockPos(100, 64, 100), 90, true,
                "minecraft:overworld", bunk, UUID.randomUUID(), UUID.randomUUID());
        PlayerCrimeData player = new PlayerCrimeData();
        player.setBunkRespawnSnapshot(snapshot);
        PlayerCrimeData loaded = new PlayerCrimeData();
        loaded.load(player.save());
        PlayerCrimeData clone = new PlayerCrimeData();
        clone.copyFrom(loaded);
        assertEquals(snapshot, clone.getBunkRespawnSnapshot());
        assertTrue(BunkRespawnPolicy.restores(bunk, "minecraft:overworld", snapshot));
        assertFalse(BunkRespawnPolicy.restores(bunk, "minecraft:the_nether", snapshot));
    }

    @Test
    void malformedSnapshotIsIgnoredWithoutThrowing() {
        CompoundTag missingDimension = new CompoundTag();
        missingDimension.putLong("bunkPos", BlockPos.ZERO.asLong());
        assertTrue(assertDoesNotThrow(() -> BunkRespawnPolicy.Snapshot.load(missingDimension)).isEmpty());
        assertTrue(BunkRespawnPolicy.Snapshot.load(null).isEmpty());
    }
}
