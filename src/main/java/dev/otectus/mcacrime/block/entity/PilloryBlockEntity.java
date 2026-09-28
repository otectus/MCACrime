package dev.otectus.mcacrime.block.entity;

import dev.otectus.mcacrime.audio.CrimeSounds;
import dev.otectus.mcacrime.block.PilloryBlock;
import dev.otectus.mcacrime.detention.DetentionRecord;
import dev.otectus.mcacrime.detention.DetentionService;
import dev.otectus.mcacrime.restraint.CustodyTransitionService;
import dev.otectus.mcacrime.state.world.CrimeWorldData;
import dev.otectus.mcacrime.tether.TetherService;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.phys.Vec3;

import org.jetbrains.annotations.Nullable;

/**
 * The pillory's per-device tick (0.7.5 M4.5).
 *
 * <p>Holds the occupant at the standing spot, counts their crouch transitions toward breaking out,
 * and runs the stale-occupancy check. It stores one thing of its own — whether the occupant was
 * crouching last tick — because a <em>transition</em> is the unit of escape work and a held crouch
 * is not a hundred of them.
 *
 * <p>The occupancy itself is not here. It is a {@code detention/DetentionRecord} in world data, so
 * this block entity unloading with its chunk does not free anybody and reloading does not need to be
 * told who it was holding.
 */
public class PilloryBlockEntity extends BlockEntity {

    private static final String TAG_WAS_CROUCHING = "WasCrouching";

    /** How often the stale-occupancy check runs. Once a second is plenty for a block. */
    private static final int SWEEP_INTERVAL_TICKS = 20;

    private boolean occupantWasCrouching;
    private int sweepCounter;

    public PilloryBlockEntity(BlockPos pos, BlockState state) {
        super(CrimeBlockEntities.PILLORY.get(), pos, state);
    }

    /**
     * One tick of holding somebody.
     *
     * <p>Cheap in the overwhelmingly common case: an empty pillory reads one map and stops.
     */
    public static void serverTick(Level level, BlockPos pos, BlockState state, PilloryBlockEntity self) {
        if (state.getValue(PilloryBlock.HALF) != DoubleBlockHalf.LOWER) {
            return;
        }
        CrimeWorldData data = PilloryBlock.data(level);
        if (data == null) {
            return;
        }
        DetentionRecord record = DetentionService.at(data, level.dimension().location(), pos)
                .orElse(null);
        if (record == null) {
            // Nobody here. If the boards say otherwise, the state is stale and is corrected once.
            if (state.getValue(PilloryBlock.CLOSED)) {
                level.setBlock(pos, state.setValue(PilloryBlock.CLOSED, Boolean.FALSE), Block.UPDATE_ALL);
            }
            return;
        }
        Entity subject = TetherService.find(level.getServer(), record.subject());
        if (!(subject instanceof LivingEntity occupant) || !occupant.isAlive()) {
            if (++self.sweepCounter >= SWEEP_INTERVAL_TICKS) {
                self.sweepCounter = 0;
                self.releaseIfOccupantGone(level, pos, record, subject);
            }
            return;
        }
        self.sweepCounter = 0;
        hold(level, pos, state, occupant);
        self.countBreakoutWork(level, pos, state, data, record, occupant);
    }

    /**
     * Keeps the occupant on the spot and facing out.
     *
     * <p>A position correction rather than a teleport packet per tick: the subject is already there,
     * and moving them the fraction of a block they have drifted is what makes the device read as
     * holding them rather than as stuttering.
     */
    private static void hold(Level level, BlockPos pos, BlockState state, LivingEntity occupant) {
        Vec3 standing = PilloryBlock.standingPosition(state.getValue(PilloryBlock.FACING), pos);
        float yaw = state.getValue(PilloryBlock.FACING).toYRot();
        occupant.setDeltaMovement(Vec3.ZERO);
        if (occupant.position().distanceToSqr(standing) > 1.0E-4D) {
            occupant.teleportTo(standing.x, standing.y, standing.z);
        }
        occupant.setYBodyRot(yaw);
        occupant.setYHeadRot(yaw);
        occupant.setYRot(yaw);
        occupant.fallDistance = 0.0F;
        occupant.hurtMarked = true;
    }

    /**
     * Counts one crouch transition, in either direction, toward breaking the device open.
     *
     * <p>Server-bounded: the count lives in the detention record, so a client that spams the sneak
     * key gains nothing an ordinary player would not, and a relog does not reset the progress.
     */
    private void countBreakoutWork(Level level, BlockPos pos, BlockState state, CrimeWorldData data,
                                   DetentionRecord record, LivingEntity occupant) {
        boolean crouching = occupant.isCrouching() || occupant.isShiftKeyDown();
        if (crouching == occupantWasCrouching) {
            return;
        }
        occupantWasCrouching = crouching;
        setChanged();
        if (!DetentionService.addBreakoutWork(data, record.id())) {
            return;
        }
        DetentionService.release(level.getServer(), data, record.id(),
                DetentionService.ReleaseReason.BROKE_OUT)
                .ifPresent(ended -> CustodyTransitionService.onDetentionEnded(level.getServer(), ended,
                        DetentionService.ReleaseReason.BROKE_OUT,
                        occupant instanceof net.minecraft.world.entity.player.Player player
                                ? player : null));
        level.levelEvent(null, 2001, pos, Block.getId(state));
        CrimeSounds.detentionBroken(level, pos);
        level.destroyBlock(pos.above(), false);
        level.destroyBlock(pos, false);
    }

    /**
     * The stale-occupancy check, run regardless of the breakout toggle.
     *
     * <p>Only an occupant who is loaded and dead, or who has walked out of the standing spot with the
     * boards closed, ends the detention. A subject whose chunk is unloaded is left exactly where the
     * record says they are.
     */
    private void releaseIfOccupantGone(Level level, BlockPos pos, DetentionRecord record,
                                       @Nullable Entity subject) {
        if (subject != null && subject.isAlive()) {
            return;
        }
        CrimeWorldData data = PilloryBlock.data(level);
        if (data == null || subject == null) {
            return; // not loaded is not gone
        }
        DetentionService.release(level.getServer(), data, record.id(),
                DetentionService.ReleaseReason.OCCUPANT_DIED)
                .ifPresent(ended -> CustodyTransitionService.onDetentionEnded(level.getServer(), ended,
                        DetentionService.ReleaseReason.OCCUPANT_DIED, null));
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.putBoolean(TAG_WAS_CROUCHING, occupantWasCrouching);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        occupantWasCrouching = tag != null && tag.getBoolean(TAG_WAS_CROUCHING);
    }
}
