package dev.otectus.mcacrime.entity;

import dev.otectus.mcacrime.McaCrimeConfig;
import dev.otectus.mcacrime.audio.CrimeSounds;
import dev.otectus.mcacrime.block.CellDoorBlock;
import dev.otectus.mcacrime.item.CrimeItems;
import dev.otectus.mcacrime.lockpick.LockpickProfile;
import dev.otectus.mcacrime.locks.LockHolder;
import dev.otectus.mcacrime.locks.LockInteractions;
import dev.otectus.mcacrime.locks.LockRecord;
import dev.otectus.mcacrime.locks.LockService;
import dev.otectus.mcacrime.locks.LockTags;
import dev.otectus.mcacrime.locks.LockTarget;
import dev.otectus.mcacrime.locks.LockTargetNormalizer;
import dev.otectus.mcacrime.state.world.CrimeWorldData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.decoration.HangingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.network.NetworkHooks;
import net.minecraftforge.registries.ForgeRegistries;

import javax.annotation.Nullable;
import java.util.Optional;
import java.util.UUID;

/**
 * A padlock hanging on somebody else's block (M3.4).
 *
 * <p>Stores the {@code lockId} and, when it has been reinforced, the id of the item that reinforced
 * it. Everything else — locked, bound, rekeyed, owner — belongs to the {@code locks} table. The
 * lock's <em>target</em> is the block the padlock hangs on, not this entity, which is what makes a
 * padlock and a safe's own lock mutually exclusive on one container rather than two competing checks
 * on it.
 *
 * <p>Two upstream defects are fixed here rather than reproduced. The reinforcement item id is stored
 * whole and parsed with {@link ResourceLocation#tryParse}, where upstream stores the full registry
 * name and then rebuilds it as {@code fromNamespaceAndPath("minecraft", "minecraft:diamond")} — a path
 * containing a colon, which is not a valid path ({@code entity/PadlockEntity.java:178} against
 * {@code :86}). And detaching is idempotent: a block broken by a player, an explosion and a piston in
 * one tick drops one padlock, not three.
 */
public class PadlockEntity extends HangingEntity
        implements LockHolder, net.minecraftforge.entity.IEntityAdditionalSpawnData {

    private static final EntityDataAccessor<Boolean> DATA_LOCKED =
            SynchedEntityData.defineId(PadlockEntity.class, EntityDataSerializers.BOOLEAN);
    private static final EntityDataAccessor<Boolean> DATA_REINFORCED =
            SynchedEntityData.defineId(PadlockEntity.class, EntityDataSerializers.BOOLEAN);

    private static final String TAG_LOCK_ID = "LockId";
    private static final String TAG_REINFORCE_ITEM = "ReinforceItem";
    private static final String TAG_GENERATED = "Generated";

    @Nullable
    private UUID lockId;
    @Nullable
    private ResourceLocation reinforcedWith;
    /** Set the moment this padlock starts coming off, so nothing can take it off twice. */
    private boolean detaching;
    /**
     * True for a padlock the mod hung itself, on a generated holding cell's door.
     *
     * <p>Such a padlock never becomes an item. It was not paid for, and a cell that is demolished,
     * picked or dug out of would otherwise mint one every time -- an arrest as a padlock farm.
     */
    private boolean generated;

    public PadlockEntity(EntityType<? extends HangingEntity> type, Level level) {
        super(type, level);
    }

    public PadlockEntity(Level level, BlockPos pos, Direction direction) {
        super(CrimeEntities.PADLOCK.get(), level, pos);
        setDirection(direction == null ? Direction.NORTH : direction);
    }

    @Override
    protected void defineSynchedData() {
        super.defineSynchedData();
        entityData.define(DATA_LOCKED, Boolean.TRUE);
        entityData.define(DATA_REINFORCED, Boolean.FALSE);
    }

    // --- the lock handle ----------------------------------------------------------------------------

    @Override
    public Optional<UUID> lockId() {
        return Optional.ofNullable(lockId);
    }

    @Override
    public boolean bindLock(UUID id) {
        if (id == null || (lockId != null && !lockId.equals(id))) {
            return false;
        }
        lockId = id;
        onLockChanged();
        return true;
    }

    /**
     * The block this padlock protects, canonicalised.
     *
     * <p>The block rather than this entity, deliberately. A padlock is an access check on a container,
     * and the container is what a hopper, an explosion and a second padlock all address.
     */
    @Override
    public LockTarget lockTarget() {
        return LockTargetNormalizer.forBlock(level(), level().dimension().location(), this.pos);
    }

    @Override
    public Component lockDisplayName() {
        return Component.translatable("entity.mcacrime.padlock");
    }

    @Override
    public LockpickProfile pickProfile(boolean reinforced) {
        return LockpickProfile.forPadlock(reinforced);
    }

    @Override
    public void onLockChanged() {
        CrimeWorldData data = LockService.data(level());
        LockRecord lock = lockId == null || data == null ? null : data.lock(lockId);
        entityData.set(DATA_LOCKED, lock == null || lock.locked());
        entityData.set(DATA_REINFORCED, reinforcedWith != null);
    }

    /** Whether this padlock draws as locked. Presentation only; the store is the authority. */
    public boolean renderLocked() {
        return entityData.get(DATA_LOCKED);
    }

    public boolean reinforced() {
        return reinforcedWith != null || entityData.get(DATA_REINFORCED);
    }

    /** Marks this padlock as the mod's own. Set once, by the cell builder, before it is added. */
    public void markGenerated() {
        generated = true;
    }

    /** Whether this padlock was hung by the mod rather than by a player. */
    public boolean isGenerated() {
        return generated;
    }

    // --- placement and survival ----------------------------------------------------------------------

    /** A padlock only hangs on a block the {@code mcacrime:lockable_blocks} tag names. */
    public boolean onSuitableBlock() {
        try {
            return level().getBlockState(this.pos).is(LockTags.LOCKABLE_BLOCKS);
        } catch (RuntimeException unloaded) {
            return true; // an unloaded chunk is not a missing block; wait rather than drop
        }
    }

    @Override
    public boolean survives() {
        return onSuitableBlock();
    }

    @Override
    public int getWidth() {
        return 8;
    }

    @Override
    public int getHeight() {
        return 8;
    }

    @Override
    public void playPlacementSound() {
        playSound(SoundEvents.CHAIN_PLACE, 1.0F, 1.0F);
    }

    /** Nothing damages a padlock: it is opened, picked or taken off with its key. */
    @Override
    public boolean hurt(net.minecraft.world.damagesource.DamageSource source, float amount) {
        return false;
    }

    // --- interaction -----------------------------------------------------------------------------------

    @Override
    public InteractionResult interact(Player interactor, InteractionHand hand) {
        if (level().isClientSide()) {
            return InteractionResult.SUCCESS;
        }
        if (!(interactor instanceof ServerPlayer player)) {
            return InteractionResult.PASS;
        }
        ItemStack stack = player.getItemInHand(hand);
        CrimeWorldData data = LockService.data(level());
        LockRecord lock = lockId == null || data == null ? null : data.lock(lockId);

        // Crouch plus a key that opens it: take the padlock back off the block, with its lock intact.
        if (player.isCrouching() && lock != null
                && LockInteractions.presented(stack, lock).isPresent()) {
            retrieve(player);
            return InteractionResult.SUCCESS;
        }
        if (reinforcement(player, stack)) {
            return InteractionResult.CONSUME;
        }
        return LockInteractions.interact(player, level(), stack, this, this.pos);
    }

    /**
     * Reinforcing: one tagged item, consumed, for a harder pick profile.
     *
     * <p>Never a harder <em>key</em>: reinforcement changes the pick difficulty and nothing else, so a
     * reinforced padlock is still opened by the key that was cut for it.
     */
    private boolean reinforcement(ServerPlayer player, ItemStack stack) {
        if (reinforcedWith != null || stack.isEmpty() || !stack.is(LockTags.CAN_REINFORCE_PADLOCK)) {
            return false;
        }
        try {
            if (!McaCrimeConfig.COMMON.allowPadlockReinforcement.get()) {
                return false;
            }
        } catch (IllegalStateException notLoaded) {
            // no config: reinforcement is allowed, which is the documented default
        }
        ResourceLocation id = ForgeRegistries.ITEMS.getKey(stack.getItem());
        if (id == null) {
            return false;
        }
        reinforcedWith = id;
        stack.shrink(1);
        CrimeWorldData data = LockService.data(level());
        if (lockId != null) {
            LockService.setReinforced(data, lockId, true);
        }
        onLockChanged();
        playSound(SoundEvents.NETHERITE_BLOCK_PLACE, 1.0F, 1.0F);
        player.displayClientMessage(Component.translatable("mcacrime.lock.reinforced"), true);
        return true;
    }

    // --- coming off --------------------------------------------------------------------------------------

    /**
     * Takes the padlock off deliberately: the owner, with their key.
     *
     * <p>The lock row survives the entity, because the key in the player's hand is still bound to it
     * and re-placing the padlock should not need a new key.
     */
    public void retrieve(@Nullable ServerPlayer player) {
        if (detaching) {
            return;
        }
        detaching = true;
        dropStacks();
        CrimeWorldData data = LockService.data(level());
        LockService.detach(data, lockId);
        playSound(SoundEvents.CHAIN_BREAK, 1.0F, 1.0F);
        discard();
    }

    /**
     * The lockpick outcome: the padlock comes off, the block it was protecting is untouched.
     *
     * <p>The padlock item is <b>not</b> returned. A picked lock is a defeated lock, and dropping an
     * intact padlock for the picker would make picking the cheapest way to acquire one.
     */
    public void pickedOpen() {
        if (detaching) {
            return;
        }
        detaching = true;
        if (reinforcedWith != null) {
            dropReinforcement();
        }
        CrimeWorldData data = LockService.data(level());
        LockService.detach(data, lockId);
        CrimeSounds.lockPicked(level(), this.pos);
        discard();
        swingOpenCellDoor();
    }

    /**
     * A picked padlock on a cell door lets the door swing open.
     *
     * <p>The padlock was the only thing holding it shut, and a door that stays closed after its lock
     * has been defeated reads as a second lock nobody can see. Any cell door, not only a generated
     * cell's: one rule for the mod's own cells and for a player-built prison alike. A padlock on a
     * chest or a vanilla door is unaffected -- those never opened on their own. Vanilla's
     * {@code setOpen} moves this half; the other half follows through its own shape update.
     */
    private void swingOpenCellDoor() {
        BlockState state;
        try {
            state = level().getBlockState(this.pos);
        } catch (RuntimeException unloaded) {
            return;
        }
        if (state.getBlock() instanceof CellDoorBlock door && !door.isOpen(state)) {
            door.setOpen(null, level(), state, this.pos, true);
        }
    }

    /**
     * Takes a generated padlock down with the cell it hung on: no item, no sound, lock detached.
     *
     * <p>Distinct from {@link #retrieve} and {@link #dropItem}, which are for padlocks somebody
     * placed. The cell builder calls this before it restores the door underneath, so the padlock never
     * finds itself unsupported and never goes through vanilla's drop path at all.
     */
    public void vanish() {
        if (detaching) {
            return;
        }
        detaching = true;
        LockService.detach(LockService.data(level()), lockId);
        discard();
    }

    /**
     * Vanilla's hanging-entity removal path: the block stopped supporting it.
     *
     * <p>Idempotent through {@link #detaching}, which is the fix for the duplicate-drop family of
     * bugs: {@code HangingEntity.tick} can call this in the same tick as a break handler, and both
     * must produce one padlock.
     */
    @Override
    public void dropItem(@Nullable Entity breaker) {
        if (detaching) {
            return;
        }
        detaching = true;
        dropStacks();
        LockService.detach(LockService.data(level()), lockId);
        playSound(SoundEvents.CHAIN_BREAK, 1.0F, 1.0F);
    }

    private void dropStacks() {
        if (!generated) {
            spawnAtLocation(new ItemStack(CrimeItems.PADLOCK.get()), 0.0F);
        }
        dropReinforcement();
    }

    /** The reinforcement material, resolved from a whole id rather than rebuilt from half of one. */
    private void dropReinforcement() {
        if (reinforcedWith == null) {
            return;
        }
        ResourceLocation id = reinforcedWith;
        reinforcedWith = null;
        net.minecraft.world.item.Item item = ForgeRegistries.ITEMS.getValue(id);
        if (item != null) {
            spawnAtLocation(new ItemStack(item), 0.0F);
        }
    }

    // --- persistence -------------------------------------------------------------------------------------

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        if (lockId != null) {
            tag.putUUID(TAG_LOCK_ID, lockId);
        }
        if (reinforcedWith != null) {
            tag.putString(TAG_REINFORCE_ITEM, reinforcedWith.toString());
        }
        if (generated) {
            tag.putBoolean(TAG_GENERATED, true);
        }
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        lockId = tag != null && tag.hasUUID(TAG_LOCK_ID) ? tag.getUUID(TAG_LOCK_ID) : null;
        reinforcedWith = tag != null && tag.contains(TAG_REINFORCE_ITEM)
                ? ResourceLocation.tryParse(tag.getString(TAG_REINFORCE_ITEM))
                : null;
        generated = tag != null && tag.getBoolean(TAG_GENERATED);
        onLockChanged();
    }

    @Override
    public net.minecraft.network.protocol.Packet<net.minecraft.network.protocol.game.ClientGamePacketListener>
            getAddEntityPacket() {
        return NetworkHooks.getEntitySpawningPacket(this);
    }

    /**
     * The block and the face it hangs on, sent with the spawn.
     *
     * <p>Entity NBT never reaches a client, and a hanging entity's whole geometry is its supporting
     * block and its facing — without this the padlock would be drawn at the right coordinates facing
     * an arbitrary way until the next relog.
     */
    @Override
    public void writeSpawnData(net.minecraft.network.FriendlyByteBuf buf) {
        buf.writeBlockPos(this.pos);
        buf.writeByte(getDirection().get2DDataValue());
    }

    @Override
    public void readSpawnData(net.minecraft.network.FriendlyByteBuf buf) {
        this.pos = buf.readBlockPos();
        setDirection(Direction.from2DDataValue(buf.readByte()));
    }

    @Override
    public ItemStack getPickResult() {
        return new ItemStack(CrimeItems.PADLOCK.get());
    }
}
