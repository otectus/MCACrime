package dev.otectus.mcacrime.gametest;

import dev.otectus.mcacrime.McaCrime;
import dev.otectus.mcacrime.block.CrimeBlocks;
import dev.otectus.mcacrime.block.entity.SafeBlockEntity;
import dev.otectus.mcacrime.locks.LockRecord;
import dev.otectus.mcacrime.locks.LockService;
import dev.otectus.mcacrime.locks.LockTargetNormalizer;
import dev.otectus.mcacrime.state.world.CrimeWorldData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.HopperBlock;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import net.neoforged.neoforge.items.IItemHandler;

import java.util.UUID;

/**
 * A lock that a hopper and a cached handler both have to obey (plan section 7.2, spec §10.3).
 *
 * <p>Three things only a running server shows. A hopper under a locked safe sees nothing, because the
 * mixin removes the container from vanilla's own lookup rather than cancelling an interaction nobody
 * made. A handler somebody obtained while the safe stood open stops working the moment it is locked,
 * because it asks per operation instead of capturing a decision. And the same protection reaches a
 * padlocked <em>vanilla</em> chest, which has no code of ours in it at all.
 */
@GameTestHolder(McaCrime.MOD_ID)
@PrefixGameTestTemplate(false)
public final class SafeAutomationGameTests {

    private SafeAutomationGameTests() {
    }

    private static LockRecord lockSafe(GameTestHelper helper, CrimeWorldData data, SafeBlockEntity safe) {
        LockRecord lock = LockService.getOrCreate(data, safe.lockTarget(), UUID.randomUUID())
                .orElseThrow(() -> new AssertionError("no lock could be created for the safe"));
        helper.assertTrue(safe.bindLock(lock.lockId()), "the safe refused its own lock");
        LockRecord locked = LockService.setLocked(data, lock.lockId(), true)
                .orElseThrow(() -> new AssertionError("the lock could not be locked"));
        safe.onLockChanged();
        return locked;
    }

    /**
     * A hopper under a locked safe pulls nothing, and pulls again the moment it is unlocked.
     *
     * <p>Both halves matter: protection that never lifts is indistinguishable from a broken hopper.
     */
    @GameTest(template = "platform", timeoutTicks = 400)
    public static void aHopperCannotDrainALockedSafe(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        CrimeWorldData data = CrimeWorldData.get(level.getServer());
        BlockPos safePos = new BlockPos(2, 2, 2);
        BlockPos hopperPos = new BlockPos(2, 1, 2);

        helper.setBlock(safePos, CrimeBlocks.SAFE.get());
        helper.setBlock(hopperPos, Blocks.HOPPER.defaultBlockState()
                .setValue(HopperBlock.FACING, Direction.DOWN));
        SafeBlockEntity safe = (SafeBlockEntity) level.getBlockEntity(helper.absolutePos(safePos));
        HopperBlockEntity hopper =
                (HopperBlockEntity) level.getBlockEntity(helper.absolutePos(hopperPos));
        helper.assertTrue(safe != null && hopper != null, "the safe or the hopper has no block entity");
        safe.setItem(0, new ItemStack(Items.DIAMOND, 8));

        LockRecord lock = lockSafe(helper, data, safe);
        try {
            for (int tick = 0; tick < 40; tick++) {
                HopperBlockEntity.pushItemsTick(level, helper.absolutePos(hopperPos),
                        level.getBlockState(helper.absolutePos(hopperPos)), hopper);
            }
            helper.assertTrue(safe.getItem(0).getCount() == 8,
                    "a hopper drained " + (8 - safe.getItem(0).getCount()) + " items out of a locked safe");
            helper.assertTrue(hopper.isEmpty(), "the hopper picked something up through the lock");

            LockService.setLocked(data, lock.lockId(), false);
            safe.onLockChanged();
            for (int tick = 0; tick < 40; tick++) {
                HopperBlockEntity.pushItemsTick(level, helper.absolutePos(hopperPos),
                        level.getBlockState(helper.absolutePos(hopperPos)), hopper);
            }
            helper.assertTrue(safe.getItem(0).getCount() < 8 || !hopper.isEmpty(),
                    "an unlocked safe is an ordinary container and the hopper never touched it");
        } finally {
            LockService.forget(data, lock.lockId());
        }
        helper.succeed();
    }

    /**
     * A handler fetched while the safe was open answers the lock, not the moment it was fetched.
     *
     * <p>Two guarantees in one test: the capability is invalidated on the lock change, and the handler
     * the caller is still holding refuses anyway. Either alone would leave a pipe network that kept
     * its reference draining a locked safe.
     */
    @GameTest(template = "platform", timeoutTicks = 200)
    public static void aCachedHandlerStopsWorkingWhenTheLockCloses(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        CrimeWorldData data = CrimeWorldData.get(level.getServer());
        BlockPos safePos = new BlockPos(3, 1, 3);
        helper.setBlock(safePos, CrimeBlocks.SAFE.get());
        BlockPos abs = helper.absolutePos(safePos);
        SafeBlockEntity safe = (SafeBlockEntity) level.getBlockEntity(abs);
        helper.assertTrue(safe != null, "the safe has no block entity");
        safe.setItem(0, new ItemStack(Items.EMERALD, 4));

        IItemHandler cached = level.getCapability(Capabilities.ItemHandler.BLOCK, abs, Direction.UP);
        helper.assertTrue(cached != null, "the safe exposes no item handler at all");
        ItemStack taken = cached.extractItem(0, 1, false);
        helper.assertTrue(taken.getCount() == 1, "an unlocked safe refused an ordinary extraction");

        LockRecord lock = lockSafe(helper, data, safe);
        try {
            helper.assertTrue(cached.extractItem(0, 1, false).isEmpty(),
                    "the handler obtained before the lock still empties the safe");
            helper.assertTrue(cached.insertItem(0, new ItemStack(Items.EMERALD), false).getCount() == 1,
                    "the handler obtained before the lock still fills the safe");
            IItemHandler fresh = level.getCapability(Capabilities.ItemHandler.BLOCK, abs, Direction.UP);
            helper.assertTrue(fresh == null || fresh.extractItem(0, 1, false).isEmpty(),
                    "a handler fetched after the lock closed is unrestricted");
            helper.assertTrue(safe.getItem(0).getCount() == 3,
                    "the locked safe's contents changed: " + safe.getItem(0));
        } finally {
            LockService.forget(data, lock.lockId());
        }
        helper.succeed();
    }

    /**
     * The same protection on a block that has no code of ours in it.
     *
     * <p>A padlock's lock record names the chest's canonical position; nothing is added to the chest
     * itself. If the hopper hook read anything but the lock table, this is where it would show.
     */
    @GameTest(template = "platform", timeoutTicks = 400)
    public static void aPadlockedVanillaChestIsInvisibleToAHopper(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        CrimeWorldData data = CrimeWorldData.get(level.getServer());
        BlockPos chestPos = new BlockPos(1, 2, 1);
        BlockPos hopperPos = new BlockPos(1, 1, 1);

        helper.setBlock(chestPos, Blocks.CHEST);
        helper.setBlock(hopperPos, Blocks.HOPPER.defaultBlockState()
                .setValue(HopperBlock.FACING, Direction.DOWN));
        Container chest = (Container) level.getBlockEntity(helper.absolutePos(chestPos));
        HopperBlockEntity hopper =
                (HopperBlockEntity) level.getBlockEntity(helper.absolutePos(hopperPos));
        helper.assertTrue(chest != null && hopper != null, "the chest or the hopper has no block entity");
        chest.setItem(0, new ItemStack(Items.GOLD_INGOT, 8));

        LockRecord lock = LockService.getOrCreate(data, LockTargetNormalizer.forBlock(level,
                        level.dimension().location(), helper.absolutePos(chestPos)), UUID.randomUUID())
                .orElseThrow(() -> new AssertionError("no lock could be created for the chest"));
        LockService.setLocked(data, lock.lockId(), true);
        try {
            helper.assertTrue(dev.otectus.mcacrime.locks.LockProtection.blocksAutomation(level,
                            helper.absolutePos(chestPos),
                            dev.otectus.mcacrime.locks.LockAutomationPolicy.Operation.EXTRACT),
                    "the padlock is not visible to the protection lookup the hopper hook asks");
            for (int tick = 0; tick < 40; tick++) {
                HopperBlockEntity.pushItemsTick(level, helper.absolutePos(hopperPos),
                        level.getBlockState(helper.absolutePos(hopperPos)), hopper);
            }
            helper.assertTrue(chest.getItem(0).getCount() == 8,
                    "a hopper drained a padlocked vanilla chest: " + chest.getItem(0));
            helper.assertTrue(hopper.isEmpty(), "the hopper picked something up through the padlock");
        } finally {
            LockService.forget(data, lock.lockId());
        }
        helper.succeed();
    }
}
