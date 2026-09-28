package dev.otectus.mcacrime.restraint;

import dev.otectus.mcacrime.item.CrimeItems;
import net.minecraft.core.BlockPos;
import net.minecraft.core.BlockSource;
import net.minecraft.core.Direction;
import net.minecraft.core.dispenser.OptionalDispenseItemBehavior;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.DispenserBlock;
import net.minecraft.world.phys.AABB;

import javax.annotation.Nullable;
import java.util.Comparator;
import java.util.List;

/**
 * A dispenser applying a restraint to whoever is standing in front of it (0.7.5 M2.6).
 *
 * <p>The correction this class exists for is the applier. The source's dispenser path calls its
 * application with {@code (player, player)} — the victim as both actor and target — so the world
 * records that the subject restrained <em>herself</em>. Everything downstream then reads wrong: a
 * kidnapping is attributed to its own victim, the release path looks for an applier who is the person
 * in the cuffs, and a self-application is supposed to be voluntary and file no case at all. Here the
 * applier is the device, at its own position, with {@link AppliedRestraint.ApplicationContext#DEVICE}.
 *
 * <p>One item is consumed on success only. A dispense that finds nobody, finds somebody already
 * wearing something on every slot the item fits, or is refused by a vulnerability gate costs nothing
 * — which is what {@link OptionalDispenseItemBehavior} is for and what the source's unconditional
 * shrink is not.
 */
public final class DispenserRestraintBehavior extends OptionalDispenseItemBehavior {

    /**
     * How far in front the dispenser reaches: the block it faces, plus one more.
     *
     * <p>Two blocks rather than one, because a subject standing between two blocks occupies both and a
     * one-block search misses them half the time.
     */
    public static final int REACH_BLOCKS = 2;

    /**
     * The order a device tries slots in: arms first, then legs, then head.
     *
     * <p>A dispenser cannot aim, so something has to decide, and the order is the restraint's own
     * severity. Arms first because that is what a trap is for; the head last because a hood on
     * somebody whose hands are free is theatre rather than a restraint.
     */
    private static final RestraintSlot[] SLOT_ORDER = {
            RestraintSlot.ARMS, RestraintSlot.LEGS, RestraintSlot.HEAD
    };

    private DispenserRestraintBehavior() {
    }

    /**
     * Registers the behaviour for every item that applies a restraint.
     *
     * <p>Called from {@code FMLCommonSetupEvent#enqueueWork}: {@code DispenserBlock}'s behaviour map is
     * a plain static map with no synchronisation, so writing to it off the main setup thread is a data
     * race with every other mod doing the same.
     */
    public static void register() {
        DispenserRestraintBehavior behavior = new DispenserRestraintBehavior();
        DispenserBlock.registerBehavior(CrimeItems.RESTRAINT_CUFFS.get(), behavior);
        DispenserBlock.registerBehavior(CrimeItems.RESTRAINT_LOCKED_CUFFS.get(), behavior);
        DispenserBlock.registerBehavior(CrimeItems.DUCK_TAPE.get(), behavior);
        // The vanilla bundle is the hood. Registering a behaviour for somebody else's item is a real
        // collision risk, so it is worth stating: vanilla gives the bundle no dispenser behaviour, and
        // this one is optional -- a dispense with nobody in front of it leaves the bundle to the
        // default path exactly as before.
        DispenserBlock.registerBehavior(Items.BUNDLE, behavior);
    }

    @Override
    protected ItemStack execute(BlockSource source, ItemStack stack) {
        setSuccess(false);
        ServerLevel level = source.getLevel();
        Direction facing = source.getBlockState().getValue(DispenserBlock.FACING);
        LivingEntity subject = nearestSubject(level, source.getPos(), facing);
        if (subject == null) {
            return stack;
        }
        RestraintSlot slot = firstFreeSlot(subject, stack);
        if (slot == null) {
            return stack;
        }
        ApplicationTransaction.Result result =
                RestraintService.applyFromDevice(level, source.getPos(), subject, stack, slot);
        if (!result.applied()) {
            return stack;
        }
        setSuccess(true);
        stack.shrink(1);
        return stack;
    }

    /**
     * The nearest restrainable subject in the two blocks the dispenser faces.
     *
     * <p>Nearest rather than first, because iteration order over a chunk's entities is not a
     * meaningful choice and "the one standing closest to the trap" is.
     */
    @Nullable
    private static LivingEntity nearestSubject(ServerLevel level, BlockPos pos, Direction facing) {
        BlockPos near = pos.relative(facing);
        BlockPos far = near.relative(facing);
        AABB area = new AABB(near).minmax(new AABB(far));
        List<LivingEntity> candidates = level.getEntitiesOfClass(LivingEntity.class, area,
                RestraintService::restrainable);
        return candidates.stream()
                .min(Comparator.comparingDouble(entity -> entity.distanceToSqr(
                        near.getX() + 0.5D, near.getY() + 0.5D, near.getZ() + 0.5D)))
                .orElse(null);
    }

    /**
     * The first slot in {@link #SLOT_ORDER} this item fits and the subject has free.
     *
     * <p>Null when there is none, which is a refusal rather than a replacement: a device may not take
     * off what somebody already put on.
     */
    @Nullable
    private static RestraintSlot firstFreeSlot(LivingEntity subject, ItemStack stack) {
        RestraintFamily family = CrimeItems.familyFor(stack).orElse(null);
        if (family == null) {
            return null;
        }
        PhysicalRestraintState state = RestraintService.state(subject);
        RigProfile rig = RestraintService.rig(subject);
        for (RestraintSlot slot : SLOT_ORDER) {
            if (state != null && state.occupied(slot)) {
                continue;
            }
            if (!rig.supports(slot)) {
                continue;
            }
            if (ApplicationTransaction.definitionFor(family, slot).isPresent()) {
                return slot;
            }
        }
        return null;
    }
}
