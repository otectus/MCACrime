package dev.otectus.mcacrime.item.lock;

import dev.otectus.mcacrime.compat.LocksReforgedBridge;
import dev.otectus.mcacrime.entity.PadlockEntity;
import dev.otectus.mcacrime.locks.ForeignLockPolicy;
import dev.otectus.mcacrime.locks.LockRecord;
import dev.otectus.mcacrime.locks.LockService;
import dev.otectus.mcacrime.locks.LockTags;
import dev.otectus.mcacrime.locks.LockTarget;
import dev.otectus.mcacrime.locks.LockTargetNormalizer;
import dev.otectus.mcacrime.state.world.CrimeWorldData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;

import java.util.Optional;

/**
 * The padlock item: places a {@link PadlockEntity} on a block the lock tag allows (M3.4, ledger L01).
 *
 * <p>Three refusals, each deliberate. A block outside {@code mcacrime:lockable_blocks} is refused,
 * which is the specification's "attach only to supported blocks, even if the source description says
 * any block". A target group that already carries a lock is refused, because one canonical owner per
 * group is what stops two access checks from disagreeing. And a target Locks Reforged already owns is
 * refused under {@code locks.foreignLockPolicy = REFUSE}, for the same reason across two mods.
 */
public class PadlockItem extends Item {

    public PadlockItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        Level level = context.getLevel();
        if (level.isClientSide() || !(context.getPlayer() instanceof ServerPlayer player)) {
            return InteractionResult.sidedSuccess(level.isClientSide());
        }
        BlockPos clicked = context.getClickedPos();
        if (!level.getBlockState(clicked).is(LockTags.LOCKABLE_BLOCKS)) {
            player.displayClientMessage(Component.translatable("mcacrime.lock.unsupported_block"), true);
            return InteractionResult.FAIL;
        }
        LockTarget target = LockTargetNormalizer.forBlock(level, level.dimension().location(), clicked);
        CrimeWorldData data = LockService.data(level);
        if (data == null) {
            return InteractionResult.FAIL;
        }
        if (LockService.at(data, target).isPresent()) {
            player.displayClientMessage(Component.translatable("mcacrime.lock.already_locked"), true);
            return InteractionResult.FAIL;
        }
        if (ForeignLockPolicy.configured() == ForeignLockPolicy.REFUSE
                && LocksReforgedBridge.ownsLock(level, target)) {
            player.displayClientMessage(Component.translatable("mcacrime.lock.foreign_owner"), true);
            return InteractionResult.FAIL;
        }
        Optional<LockRecord> lock = LockService.create(data, target, player.getUUID());
        if (lock.isEmpty()) {
            return InteractionResult.FAIL;
        }
        Direction face = context.getClickedFace();
        PadlockEntity padlock = new PadlockEntity(level, clicked,
                face.getAxis().isVertical() ? player.getDirection().getOpposite() : face);
        if (!padlock.survives() || !padlock.bindLock(lock.get().lockId())) {
            LockService.forget(data, lock.get().lockId());
            return InteractionResult.FAIL;
        }
        padlock.playPlacementSound();
        level.addFreshEntity(padlock);
        ItemStack stack = context.getItemInHand();
        if (!player.isCreative()) {
            stack.shrink(1);
        }
        player.displayClientMessage(Component.translatable("mcacrime.lock.padlock_placed"), true);
        return InteractionResult.CONSUME;
    }
}
