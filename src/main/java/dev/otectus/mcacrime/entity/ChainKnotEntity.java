package dev.otectus.mcacrime.entity;

import dev.otectus.mcacrime.McaCrimeConfig;
import dev.otectus.mcacrime.state.world.CrimeWorldData;
import dev.otectus.mcacrime.tether.TetherKind;
import dev.otectus.mcacrime.tether.TetherRecord;
import dev.otectus.mcacrime.tether.TetherService;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.decoration.HangingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.network.NetworkHooks;

import javax.annotation.Nullable;
import java.util.List;
import java.util.UUID;

/**
 * The knot a chain is tied to on a fence post or a tripwire hook (0.7.5 M4.2).
 *
 * <p>Persistent, multi-subject, and its own end of a {@link TetherRecord}: several subjects may be
 * tied to one knot, and the knot outlives a chunk unload because it is an entity like vanilla's own
 * lead knot. It stores nothing but its position — who is tied to it is the tether table's answer, and
 * the reverse index makes it a map read rather than the source's world-wide entity scan
 * ({@code event/ModServerEvents.java:165-171}).
 *
 * <p>Two upstream defects are fixed rather than reproduced. {@code isOnFence()} returns
 * {@code !…is(BlockTags.FENCES)} — inverted relative to its name ({@code entity/ChainKnotEntity.java:55-57});
 * {@link #onSupportingBlock()} here says what it means. And upstream's {@code interact} discards the
 * knot unconditionally after a failed transfer, "even when nothing was detached" ({@code :96-107});
 * this one only discards a knot that is genuinely holding nothing.
 */
public class ChainKnotEntity extends HangingEntity
        implements net.minecraftforge.entity.IEntityAdditionalSpawnData {

    /** How far off a block centre a knot may sit and still be "the knot at that block". */
    private static final double SEARCH_RADIUS = 1.0D;

    /** Where the chain visually leaves the knot, matching vanilla's lead knot. */
    private static final double OFFSET_Y = 0.375D;

    private boolean detaching;

    public ChainKnotEntity(EntityType<? extends ChainKnotEntity> type, Level level) {
        super(type, level);
    }

    public ChainKnotEntity(Level level, BlockPos pos) {
        super(CrimeEntities.CHAIN_KNOT.get(), level, pos);
        setPos(pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D);
    }

    @Override
    protected void recalculateBoundingBox() {
        setPosRaw(this.pos.getX() + 0.5D, this.pos.getY() + 0.5D, this.pos.getZ() + 0.5D);
        double half = getType().getWidth() / 2.0D;
        double height = getType().getHeight();
        setBoundingBox(new AABB(getX() - half, getY(), getZ() - half,
                getX() + half, getY() + height, getZ() + half));
    }

    @Override
    public void setDirection(Direction direction) {
        // A knot hangs on a post, not on a face. Vanilla's lead knot ignores the direction for the
        // same reason, and accepting one would make the same knot two different entities.
    }

    @Override
    public int getWidth() {
        return 9;
    }

    @Override
    public int getHeight() {
        return 9;
    }

    @Override
    public boolean shouldRenderAtSqrDistance(double distanceSqr) {
        return distanceSqr < 1024.0D;
    }

    /** Where a chain drawn to this knot should end. */
    public Vec3 getRopeHoldPosition(float partialTick) {
        return getPosition(partialTick).add(0.0D, OFFSET_Y, 0.0D);
    }

    // --- survival --------------------------------------------------------------------------------

    /**
     * Whether the block this knot hangs on is one a chain may be tied to.
     *
     * <p>Says what its name says, unlike the source's inverted {@code isOnFence}. Config may narrow
     * the set — a server that does not want fence anchors switches them off — but an unloaded chunk
     * answers "yes", because an unloaded block is not a missing one and dropping every knot in an
     * unvisited chunk is the wrong direction to be wrong in.
     */
    public boolean onSupportingBlock() {
        try {
            var state = level().getBlockState(this.pos);
            if (state.is(BlockTags.FENCES) || state.is(BlockTags.WALLS)) {
                return allowFenceAnchors();
            }
            if (state.is(Blocks.TRIPWIRE_HOOK)) {
                return allowTripwireHookAnchors();
            }
            return false;
        } catch (RuntimeException unloaded) {
            return true;
        }
    }

    @Override
    public boolean survives() {
        return onSupportingBlock();
    }

    private static boolean allowFenceAnchors() {
        try {
            return McaCrimeConfig.COMMON.allowFenceAnchors.get();
        } catch (IllegalStateException | NullPointerException notLoaded) {
            return true;
        }
    }

    private static boolean allowTripwireHookAnchors() {
        try {
            return McaCrimeConfig.COMMON.allowTripwireHookAnchors.get();
        } catch (IllegalStateException | NullPointerException notLoaded) {
            return true;
        }
    }

    // --- lookup -----------------------------------------------------------------------------------

    /** The knot already at {@code pos}, or null. Bounded to one block, never a world scan. */
    @Nullable
    public static ChainKnotEntity at(@Nullable Level level, @Nullable BlockPos pos) {
        if (level == null || pos == null) {
            return null;
        }
        double x = pos.getX() + 0.5D;
        double y = pos.getY() + 0.5D;
        double z = pos.getZ() + 0.5D;
        List<ChainKnotEntity> found = level.getEntitiesOfClass(ChainKnotEntity.class,
                new AABB(x - SEARCH_RADIUS, y - SEARCH_RADIUS, z - SEARCH_RADIUS,
                        x + SEARCH_RADIUS, y + SEARCH_RADIUS, z + SEARCH_RADIUS));
        for (ChainKnotEntity knot : found) {
            if (knot.getPos().equals(pos)) {
                return knot;
            }
        }
        return null;
    }

    /** The knot at {@code pos}, creating and spawning one when there is none. */
    @Nullable
    public static ChainKnotEntity getOrCreate(@Nullable Level level, @Nullable BlockPos pos) {
        ChainKnotEntity existing = at(level, pos);
        if (existing != null) {
            return existing;
        }
        if (level == null || pos == null) {
            return null;
        }
        ChainKnotEntity knot = new ChainKnotEntity(level, pos);
        if (!knot.survives()) {
            return null;
        }
        level.addFreshEntity(knot);
        knot.playPlacementSound();
        return knot;
    }

    // --- interaction ------------------------------------------------------------------------------

    /**
     * Right-clicking the knot: hand over whatever the interactor is leading, or untie everything.
     *
     * <p>The order matters and is the source's, minus its unconditional discard. A player leading
     * somebody transfers them onto the knot; a player leading nobody unties whatever the knot holds
     * and the knot goes with it. A knot that was holding nothing and received nothing is left alone,
     * because discarding it there would delete a knot somebody had just tied and not yet used.
     */
    @Override
    public InteractionResult interact(Player interactor, InteractionHand hand) {
        if (level().isClientSide()) {
            return InteractionResult.SUCCESS;
        }
        CrimeWorldData data = TetherService.data(level());
        if (data == null) {
            return InteractionResult.PASS;
        }
        if (interactor instanceof net.minecraft.server.level.ServerPlayer actor
                && !dev.otectus.mcacrime.enforcement.RestraintHandlers.permits(actor,
                        dev.otectus.mcacrime.restraint.RestraintAction.INTERACT_ENTITY)) {
            return InteractionResult.PASS;
        }
        int transferred = transferFrom(data, interactor);
        if (transferred > 0) {
            playSound(SoundEvents.CHAIN_PLACE, 1.0F, 1.0F);
            return InteractionResult.CONSUME;
        }
        int released = TetherService.detachAtAnchor(level().getServer(),
                level().dimension().location(), this.pos, TetherService.DetachReason.RELEASED);
        if (released > 0) {
            playSound(SoundEvents.CHAIN_BREAK, 1.0F, 1.0F);
            discardKnot();
            return InteractionResult.CONSUME;
        }
        return InteractionResult.PASS;
    }

    /**
     * Moves everybody {@code holder} is leading onto this knot.
     *
     * <p>A transfer, not a re-attachment: the existing tether ends and a new one to the knot begins,
     * and the chain item is carried across rather than paid back and taken again. Otherwise tying a
     * prisoner to a fence would mint a chain into the holder's inventory and consume one from it in
     * the same tick, which is a duplication bug waiting for the second half to fail.
     */
    public int transferFrom(CrimeWorldData data, @Nullable Entity holder) {
        if (holder == null) {
            return 0;
        }
        List<TetherRecord> held = TetherService.index(data).forHolder(holder.getUUID());
        int moved = 0;
        for (TetherRecord tether : held) {
            if (tether.kind() == TetherKind.ESCORT) {
                continue; // an escort is a legal hold, not a chain; it is not tied to fence posts
            }
            Entity subject = TetherService.find(level().getServer(), tether.subject());
            if (subject == null) {
                continue;
            }
            UUID owner = tether.chainOwner();
            boolean owed = tether.returnOnRelease();
            if (TetherService.evaluateAnchorTransfer(data, subject, this, holder.getUUID())
                    != TetherService.Refusal.NONE) {
                continue;
            }
            // Carried across: the row is removed without paying the chain back, then re-made against
            // the knot with the same owner, so exactly one chain exists throughout.
            if (!data.removeTether(tether.id())) {
                continue;
            }
            TetherService.index(data).remove(tether.id());
            if (TetherService.attachToAnchor(data, subject, this, this.pos, holder.getUUID(), owner,
                    owed).isPresent()) {
                moved++;
            } else {
                // The preflight passed but the store refused the replacement. Restore the paid-for
                // chain row; the subject state still points at its id because no detach occurred.
                data.putTether(tether);
                TetherService.index(data).put(tether);
            }
        }
        return moved;
    }

    /** Whether this knot is holding anything at all. A map read. */
    public boolean holdingAnything() {
        CrimeWorldData data = TetherService.data(level());
        return data != null && TetherService.anchored(data, level().dimension().location(), this.pos);
    }

    // --- removal -----------------------------------------------------------------------------------

    /**
     * Vanilla's hanging-entity removal path: the supporting block went away.
     *
     * <p>Idempotent through {@link #detaching}, the same guard the padlock uses, because a block
     * broken by a player, an explosion and a piston in one tick must untie one knot and drop one
     * chain per subject rather than three.
     */
    @Override
    public void dropItem(@Nullable Entity breaker) {
        if (detaching) {
            return;
        }
        detaching = true;
        TetherService.detachAtAnchor(level().getServer(), level().dimension().location(), this.pos,
                TetherService.DetachReason.HOLDER_LOST);
        playSound(SoundEvents.CHAIN_BREAK, 1.0F, 1.0F);
    }

    /** Takes the knot away once whatever it held is untied. */
    public void discardKnot() {
        if (detaching) {
            return;
        }
        detaching = true;
        discard();
    }

    @Override
    public void playPlacementSound() {
        playSound(SoundEvents.CHAIN_PLACE, 1.0F, 1.0F);
    }

    /** A knot is not a target: it is untied, never fought. */
    @Override
    public boolean hurt(net.minecraft.world.damagesource.DamageSource source, float amount) {
        return false;
    }

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        // Position and facing are the base class's; a knot has no state of its own, by design.
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
    }

    @Override
    public net.minecraft.network.protocol.Packet<net.minecraft.network.protocol.game.ClientGamePacketListener>
            getAddEntityPacket() {
        return NetworkHooks.getEntitySpawningPacket(this);
    }

    /**
     * The supporting block, sent with the spawn.
     *
     * <p>Entity NBT never reaches a client, and a knot's whole geometry is the block it hangs on.
     * Without this the client would place it from the spawn packet's coordinates and then snap it
     * elsewhere the first time anything recalculated its box.
     */
    @Override
    public void writeSpawnData(net.minecraft.network.FriendlyByteBuf buf) {
        buf.writeBlockPos(this.pos);
    }

    @Override
    public void readSpawnData(net.minecraft.network.FriendlyByteBuf buf) {
        this.pos = buf.readBlockPos();
        recalculateBoundingBox();
    }

    @Override
    public ItemStack getPickResult() {
        return new ItemStack(Items.CHAIN);
    }
}
