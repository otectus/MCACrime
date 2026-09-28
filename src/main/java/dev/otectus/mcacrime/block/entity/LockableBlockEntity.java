package dev.otectus.mcacrime.block.entity;

import dev.otectus.mcacrime.locks.LockRecord;
import dev.otectus.mcacrime.locks.LockService;
import dev.otectus.mcacrime.locks.LockTarget;
import dev.otectus.mcacrime.locks.LockTargetNormalizer;
import dev.otectus.mcacrime.state.world.CrimeWorldData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;

import org.jetbrains.annotations.Nullable;
import java.util.Optional;
import java.util.UUID;

/**
 * A block that can carry a lock. It stores the {@code lockId} and nothing else (§1.5).
 *
 * <p>No {@code locked} flag, no lock name, no "has been bound" boolean: every one of those is a second
 * copy of a fact the {@code locks} table already owns, and a second copy is a chance for the block and
 * the world store to disagree about whether a door is locked. What lives here is the handle.
 *
 * <p>{@link #loadAdditional} is guarded. Upstream reads its UUID with no {@code contains} check
 * ({@code blocks/entity/LockableBlockEntity.java:50}), so a block entity written by an older build,
 * an NBT editor or a failed write throws on load and takes the chunk with it. An unbound door is a
 * perfectly ordinary thing to be.
 *
 * <p>1.21.1 note: {@code load} is {@code loadAdditional(CompoundTag, HolderLookup.Provider)} and the
 * save side takes the same provider. Nothing here needs the lookup — a UUID names no registry entry —
 * but the signature is the one vanilla calls, and getting it wrong is a silent no-op rather than a
 * compile error on the superclass.
 */
public class LockableBlockEntity extends BlockEntity implements dev.otectus.mcacrime.locks.LockHolder {

    private static final String TAG_LOCK_ID = "LockId";

    @Nullable
    private UUID lockId;

    public LockableBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
    }

    public LockableBlockEntity(BlockPos pos, BlockState state) {
        this(CrimeBlockEntities.CELL_DOOR.get(), pos, state);
    }

    /** The lock this block carries, if it has ever been bound. */
    @Override
    public Optional<UUID> lockId() {
        return Optional.ofNullable(lockId);
    }

    public boolean bound() {
        return lockId != null;
    }

    /** Binds this block to a lock. Idempotent; never silently replaces a different one. */
    @Override
    public boolean bindLock(@Nullable UUID id) {
        if (id == null || (lockId != null && !lockId.equals(id))) {
            return false;
        }
        if (id.equals(lockId)) {
            return true;
        }
        lockId = id;
        setChanged();
        return true;
    }

    /**
     * Forgets the lock handle.
     *
     * <p>Not a rekey: rekeying is {@code LockService.rekey}, which keeps the identity and invalidates
     * the keys. This is for a block that is being removed.
     */
    public void unbind() {
        if (lockId != null) {
            lockId = null;
            setChanged();
        }
    }

    /** The lock record behind this block, if the store has one. */
    public Optional<LockRecord> lock(@Nullable CrimeWorldData data) {
        return LockService.byId(data, lockId);
    }

    /** The canonical target for this block's group. */
    @Override
    public LockTarget lockTarget() {
        if (level == null) {
            return LockTarget.none();
        }
        return LockTargetNormalizer.forBlock(level, level.dimension().location(), worldPosition);
    }

    @Override
    public net.minecraft.network.chat.Component lockDisplayName() {
        return getBlockState().getBlock().getName();
    }

    /**
     * How hard this block is to pick.
     *
     * <p>The cell-door profile for a cell door and the safe profile for anything else that ends up
     * carrying one of these: the two are the only block profiles §8 defines, and a lockable block
     * that is neither is closer to a safe than to a prison door.
     */
    @Override
    public dev.otectus.mcacrime.lockpick.LockpickProfile pickProfile(boolean reinforced) {
        return getBlockState().getBlock() instanceof dev.otectus.mcacrime.block.CellDoorBlock
                ? dev.otectus.mcacrime.lockpick.LockpickProfile.CELL_DOOR
                : dev.otectus.mcacrime.lockpick.LockpickProfile.SAFE;
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        if (lockId != null) {
            tag.putUUID(TAG_LOCK_ID, lockId);
        }
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        lockId = tag != null && tag.hasUUID(TAG_LOCK_ID) ? tag.getUUID(TAG_LOCK_ID) : null;
    }
}
