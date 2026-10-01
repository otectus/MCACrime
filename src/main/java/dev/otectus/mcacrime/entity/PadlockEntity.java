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
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerEntity;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.decoration.HangingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoorHingeSide;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.neoforged.neoforge.entity.IEntityWithComplexSpawn;

import org.jetbrains.annotations.Nullable;
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
 *
 * <p>1.21.1 note: a hanging entity declares its own box through {@code calculateBoundingBox} rather
 * than a width and height in pixels, synched data is defined on a builder, and the extra spawn data
 * rides on NeoForge's {@link IEntityWithComplexSpawn} rather than a Forge spawn packet.
 */
public class PadlockEntity extends HangingEntity implements LockHolder, IEntityWithComplexSpawn {

    private static final EntityDataAccessor<Boolean> DATA_LOCKED =
            SynchedEntityData.defineId(PadlockEntity.class, EntityDataSerializers.BOOLEAN);
    private static final EntityDataAccessor<Boolean> DATA_REINFORCED =
            SynchedEntityData.defineId(PadlockEntity.class, EntityDataSerializers.BOOLEAN);

    private static final String TAG_LOCK_ID = "LockId";
    private static final String TAG_REINFORCE_ITEM = "ReinforceItem";
    private static final String TAG_GENERATED = "Generated";

    /** Which way the padlock faces. {@code BlockAttachedEntity} saves only the block; each kind saves its own. */
    private static final String TAG_FACING = "Facing";
    /** How often the drawn position is re-read from the block, in ticks: a door can change form. */
    private static final int RECHECK_INTERVAL_TICKS = 20;

    /** From the entity's own position to where the padlock is drawn. Client presentation only. */
    private Vec3 renderOffset = Vec3.ZERO;

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
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        builder.define(DATA_LOCKED, Boolean.TRUE);
        builder.define(DATA_REINFORCED, Boolean.FALSE);
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
    protected AABB calculateBoundingBox(BlockPos pos, Direction direction) {
        // Puts the padlock on the surface of the block it locks (see PadlockPlacement). The old box was
        // vanilla's item-frame arithmetic, which assumes the entity's block is the air in front of the
        // support and so hung every padlock on the far side of the block this one locks. The block's
        // closed shape is used, so a padlock on an unlocked door that swings open stays where the door
        // shuts; on a door it hangs at the lock plate.
        BlockState host = hostState(pos);
        PadlockPlacement.Door door = null;
        if (host.getBlock() instanceof DoorBlock) {
            Direction facing = host.getValue(DoorBlock.FACING);
            door = new PadlockPlacement.Door(host.getValue(DoorBlock.HALF) == DoubleBlockHalf.UPPER,
                    PadlockPlacement.plateSide(facing, host.getValue(DoorBlock.HINGE) == DoorHingeSide.LEFT,
                            host.getBlock() instanceof CellDoorBlock));
        }
        Vec3 visual = PadlockPlacement.visual(pos, direction, closedBounds(host, pos), door);
        Vec3 anchor = PadlockPlacement.anchor(pos, visual);
        renderOffset = visual.subtract(anchor);
        // HangingEntity puts the entity at this box's centre, and that centre has to stay inside the
        // locked block, because setPos turns a position back into a block. So the box is centred on the
        // anchor and grown until it holds the padlock where it is drawn.
        AABB drawn = PadlockPlacement.box(visual, direction);
        return AABB.ofSize(anchor,
                2.0D * Math.max(Math.abs(drawn.minX - anchor.x), Math.abs(drawn.maxX - anchor.x)),
                2.0D * Math.max(Math.abs(drawn.minY - anchor.y), Math.abs(drawn.maxY - anchor.y)),
                2.0D * Math.max(Math.abs(drawn.minZ - anchor.z), Math.abs(drawn.maxZ - anchor.z)));
    }

    /** On a door, always one of its two broad faces: a padlock hung on the edge by an older build moves to the front. */
    @Override
    protected void setDirection(Direction facing) {
        BlockState host = pos == null ? null : hostState(pos);
        if (host != null && facing != null && host.getBlock() instanceof DoorBlock
                && facing.getAxis() != host.getValue(DoorBlock.FACING).getAxis()) {
            facing = host.getValue(DoorBlock.FACING);
        }
        super.setDirection(facing);
    }

    /** From this entity's position to where it is drawn; the renderer translates by it. */
    public Vec3 renderOffset() {
        return renderOffset;
    }

    @Override
    public void tick() {
        super.tick();
        if (!isRemoved() && tickCount % RECHECK_INTERVAL_TICKS == 0) {
            // A cell door turns barred or plain as bars come and go beside it, and moves its face by
            // seven pixels when it does.
            recalculateBoundingBox();
        }
    }

    /** The block a padlock at {@code at} hangs on, or air while it is not loaded. Never loads a chunk. */
    private BlockState hostState(BlockPos at) {
        try {
            Level level = level();
            if (level != null && level.isLoaded(at)) {
                return level.getBlockState(at);
            }
        } catch (RuntimeException unavailable) {
            // fall through: hang as if on a full block until the next recalculation
        }
        return net.minecraft.world.level.block.Blocks.AIR.defaultBlockState();
    }

    /** The host's shape with any door, gate or trapdoor shut, in block-local coordinates. */
    @Nullable
    private AABB closedBounds(BlockState host, BlockPos at) {
        if (host.isAir()) {
            return null;
        }
        try {
            BlockState closed = host.hasProperty(BlockStateProperties.OPEN)
                    ? host.setValue(BlockStateProperties.OPEN, Boolean.FALSE) : host;
            VoxelShape shape = closed.getShape(level(), at);
            return shape.isEmpty() ? null : shape.bounds();
        } catch (RuntimeException unavailable) {
            return null;
        }
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
        ResourceLocation id = BuiltInRegistries.ITEM.getKey(stack.getItem());
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
     * Vanilla's block-attached removal path: the block stopped supporting it.
     *
     * <p>Idempotent through {@link #detaching}, which is the fix for the duplicate-drop family of
     * bugs: the entity's own tick can call this in the same tick as a break handler, and both must
     * produce one padlock.
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
        Item item = BuiltInRegistries.ITEM.get(id);
        if (item != null && item != net.minecraft.world.item.Items.AIR) {
            spawnAtLocation(new ItemStack(item), 0.0F);
        }
    }

    // --- persistence -------------------------------------------------------------------------------------

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        tag.putByte(TAG_FACING, (byte) getDirection().get2DDataValue());
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
        // The facing was never saved before, so a reload turned every padlock to face south and hung it
        // on that side of its block. A save from then still carries the yaw the facing set, which is
        // exactly the facing back again.
        setDirection(tag != null && tag.contains(TAG_FACING)
                ? Direction.from2DDataValue(tag.getByte(TAG_FACING))
                : Direction.fromYRot(getYRot()));
        onLockChanged();
    }

    @Override
    public Packet<ClientGamePacketListener> getAddEntityPacket(ServerEntity serverEntity) {
        return new ClientboundAddEntityPacket(this, serverEntity);
    }

    /**
     * The block and the face it hangs on, sent with the spawn.
     *
     * <p>Entity NBT never reaches a client, and a block-attached entity's whole geometry is its
     * supporting block and its facing — without this the padlock would be drawn at the right
     * coordinates facing an arbitrary way until the next relog.
     */
    @Override
    public void writeSpawnData(RegistryFriendlyByteBuf buf) {
        buf.writeBlockPos(this.pos);
        buf.writeByte(getDirection().get2DDataValue());
    }

    @Override
    public void readSpawnData(RegistryFriendlyByteBuf buf) {
        this.pos = buf.readBlockPos();
        setDirection(Direction.from2DDataValue(buf.readByte()));
    }

    @Override
    public ItemStack getPickResult() {
        return new ItemStack(CrimeItems.PADLOCK.get());
    }
}
