package dev.otectus.mcacrime.locks;

import dev.otectus.mcacrime.state.world.CrimeWorldData;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class FirstKeyInitializationTest {
    @Test
    void onlyOwnerCanInitializeOnceAndRekeyInvalidatesOldKey() {
        CrimeWorldData data = new CrimeWorldData();
        UUID owner = UUID.randomUUID();
        LockRecord created = LockService.create(data, LockTarget.block(
                new ResourceLocation("minecraft:overworld"), BlockPos.ZERO), owner).orElseThrow();
        assertTrue(LockService.initializeKey(data, created.lockId(), UUID.randomUUID(), false).isEmpty());
        LockRecord issued = LockService.initializeKey(data, created.lockId(), owner, false).orElseThrow();
        KeyBinding oldKey = KeyBinding.forLock(issued, null);
        assertTrue(issued.keyInitialized());
        assertTrue(LockService.initializeKey(data, created.lockId(), owner, false).isEmpty());
        LockRecord rekeyed = LockService.rekey(data, created.lockId()).orElseThrow();
        assertFalse(oldKey.opens(rekeyed));
        assertFalse(rekeyed.keyInitialized());
        assertTrue(LockService.initializeKey(data, created.lockId(), owner, false).isPresent());
    }

    @Test
    void saveReloadPreservesIssuanceAndLegacyDoesNotOpenACopyWindow() {
        LockRecord lock = LockRecord.of(UUID.randomUUID(), LockTarget.none(), UUID.randomUUID());
        assertFalse(LockRecord.load(lock.save()).orElseThrow().keyInitialized());
        assertTrue(LockRecord.load(lock.markKeyInitialized().save()).orElseThrow().keyInitialized());
        CompoundTag old = lock.save();
        old.remove("keyInitialized");
        assertTrue(LockRecord.load(old).orElseThrow().keyInitialized());
    }
}
