package dev.otectus.mcacrime.block.entity;

import dev.otectus.mcacrime.McaCrimeConfig;
import dev.otectus.mcacrime.audio.CrimeSounds;
import dev.otectus.mcacrime.block.GuillotineBlock;
import dev.otectus.mcacrime.block.PilloryBlock;
import dev.otectus.mcacrime.detention.DetentionRecord;
import dev.otectus.mcacrime.detention.DetentionService;
import dev.otectus.mcacrime.detention.ExecutionAuthorization;
import dev.otectus.mcacrime.restraint.CustodyTransitionService;
import dev.otectus.mcacrime.state.world.CrimeWorldData;
import dev.otectus.mcacrime.tether.TetherDamage;
import dev.otectus.mcacrime.tether.TetherService;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import org.jetbrains.annotations.Nullable;
import java.util.UUID;

/**
 * The blade, its delay, and the single attributed blow (0.7.5 M4.6, M4.10).
 *
 * <p>Four upstream defects are fixed here rather than reproduced.
 *
 * <ul>
 *   <li><b>The double kill.</b> A dangling else runs both {@code player.kill()} and
 *       {@code hurt(..., Float.MAX_VALUE)} unconditionally
 *       ({@code blocks/entity/GuillotineBlockEntity.java:308-313}). Here there is exactly one
 *       {@code hurt} through the ordinary damage pipeline, so totems, invulnerability, PlayerRevive
 *       and any other mod's cancellation all still work — and a cancelled death resolves nothing.</li>
 *   <li><b>The victim blamed for their own death.</b> The source's damage source substitutes the
 *       victim as the responsible entity ({@code init/ModDamageTypes.java:24}). Here the actor who
 *       gave the order is the responsible entity.</li>
 *   <li><b>The unpersisted delay.</b> {@code chopDelay} is a field the source never saves
 *       ({@code :327} against {@code :339-344}), so an unload inside the five-tick window cancels the
 *       execution silently. It is persisted here, along with a completion marker, so an unload can
 *       neither cancel nor repeat it.</li>
 *   <li><b>The fabricated head.</b> The head drop reads the victim's game profile unconditionally.
 *       Here only a player drops a player head; a villager drops nothing extra, because a villager
 *       has no head item and inventing one would be this mod making up loot.</li>
 * </ul>
 */
public class GuillotineBlockEntity extends BlockEntity {

    private static final String TAG_BLADE_DOWN = "BladeDown";
    private static final String TAG_BLOODY = "Bloody";
    private static final String TAG_DELAY = "ChopDelay";
    private static final String TAG_TARGET = "PendingTarget";
    private static final String TAG_ACTOR = "PendingActor";
    private static final String TAG_DONE = "Completed";

    private boolean bladeDown;
    private boolean bloody;

    /** Ticks left before the blow lands. Persisted; zero means nothing is in flight. */
    private int chopDelay;

    /** Who the falling blade is aimed at, pinned when the lever was pulled. */
    @Nullable
    private UUID pendingTarget;

    /** Who pulled it. The damage is attributed to them, and to nobody else. */
    @Nullable
    private UUID pendingActor;

    /**
     * The completion marker.
     *
     * <p>Persisted, and the whole reason a restart inside the window cannot double-run the blow: a
     * device that has already struck is a device that has struck, whatever its delay field says.
     */
    private boolean completed;

    public GuillotineBlockEntity(BlockPos pos, BlockState state) {
        super(CrimeBlockEntities.GUILLOTINE.get(), pos, state);
    }

    // --- the lever ------------------------------------------------------------------------------------

    /**
     * Somebody pulled the lever.
     *
     * <p>Raising the blade resets the device and cancels nothing that has already landed. Dropping it
     * starts the delay and pins the target — but only <em>arms</em> the blow if a live authorisation
     * names this subject, this device and this actor. Without one the blade still falls: the device
     * works, it simply takes nobody's life.
     */
    public void pullLever(Level level, BlockPos pos, BlockState state, @Nullable Player actor) {
        if (level.isClientSide()) {
            return;
        }
        if (bladeDown) {
            raise(level, pos, state);
            return;
        }
        bladeDown = true;
        completed = false;
        chopDelay = activationDelayTicks();
        LivingEntity occupant = occupant(level, pos);
        pendingTarget = occupant == null ? null : occupant.getUUID();
        pendingActor = actor == null ? null : actor.getUUID();
        if (occupant != null && actor != null) {
            armOrExplain(level, pos, occupant, actor);
        }
        apply(level, pos, state);
        CrimeSounds.guillotineUsed(level, pos, true);
    }

    /**
     * Records the order, or tells the actor exactly why there is none.
     *
     * <p>Arming is a deliberate act with an actor, which is the guarantee: this is the only call into
     * {@link ExecutionAuthorization#arm}, it is reached only from a right-click, and it never runs
     * without a player behind it.
     */
    private void armOrExplain(Level level, BlockPos pos, LivingEntity occupant, Player actor) {
        CrimeWorldData data = PilloryBlock.data(level);
        DetentionRecord detention = data == null ? null
                : DetentionService.forSubject(data, occupant.getUUID()).orElse(null);
        long generation = detention == null ? 1L : detention.occupantGeneration();
        var armed = ExecutionAuthorization.arm(level.getServer(), occupant.getUUID(), actor.getUUID(),
                true, false, level.dimension().location(), GuillotineBlock.devicePos(pos),
                level.getGameTime(), generation);
        if (armed.isEmpty()) {
            actor.displayClientMessage(Component.translatable(
                    ExecutionAuthorization.messageKey(ExecutionAuthorization.lastRefusal())), true);
            return;
        }
        actor.displayClientMessage(
                Component.translatable(ExecutionAuthorization.messageKey(
                        ExecutionAuthorization.Refusal.NONE)), true);
        CrimeSounds.guillotineArmed(level, pos);
    }

    /**
     * A guard drops the blade on a prisoner they have already been authorised to execute (M6.7).
     *
     * <p>Separate from {@link #pullLever} because a guard is not a {@code Player} and because the two
     * halves happen at different moments: the order was armed when the condemned prisoner was placed
     * in the device, and the ceremony window has since run. This drops the blade and nothing else -
     * it creates no authorisation, so if the order was cleared inside the window by a pardon, a
     * commutation, a rescue or the guard's own death, the blade falls on a living prisoner and
     * {@link #strikes} refuses, which is exactly the intended behaviour.
     *
     * @param actor the guard the death is attributed to; never null, because an execution with no
     *              actor is the automatic death §3.19 rules out
     * @return false when the device is already down or has already struck
     */
    public boolean dropBladeFor(Level level, BlockPos pos, BlockState state, @Nullable UUID subject,
                                @Nullable UUID actor) {
        if (level.isClientSide() || bladeDown || completed || subject == null || actor == null) {
            return false;
        }
        bladeDown = true;
        chopDelay = activationDelayTicks();
        pendingTarget = subject;
        pendingActor = actor;
        apply(level, pos, state);
        CrimeSounds.guillotineUsed(level, pos, true);
        return true;
    }

    /** Raises the blade and resets the device. Any order armed at it is cleared, not carried out. */
    public void raise(Level level, BlockPos pos, BlockState state) {
        bladeDown = false;
        chopDelay = 0;
        if (pendingTarget != null) {
            ExecutionAuthorization.clear(pendingTarget, ExecutionAuthorization.ClearReason.EXPIRED);
        }
        pendingTarget = null;
        pendingActor = null;
        completed = false;
        apply(level, pos, state);
        CrimeSounds.guillotineUsed(level, pos, false);
    }

    // --- the tick --------------------------------------------------------------------------------------

    public static void serverTick(Level level, BlockPos pos, BlockState state,
                                  GuillotineBlockEntity self) {
        if (self.chopDelay <= 0) {
            return;
        }
        self.chopDelay--;
        self.setChanged();
        if (self.chopDelay == 0) {
            self.strike(level, pos, state);
        }
    }

    /**
     * The blow: at most one attributed damage action, and only under a live authorisation.
     *
     * <p>Every early return is a case in which the device opens instead of killing, which is the
     * specification's rule: an unlawful, unsentenced or merely restrained subject in a guillotine can
     * still be released, never executed.
     */
    /**
     * Whether the blow may land at all.
     *
     * <p>Pure, and the fix for the double kill stated as one rule: four facts, one answer, one
     * {@code hurt}. The completion marker is checked here rather than assumed from the delay, which is
     * why a chunk unload inside the window can neither cancel the blow nor run it twice.
     */
    public static boolean strikes(boolean alreadyCompleted, boolean targetPresent, boolean featureEnabled,
                                  boolean authorised) {
        return !alreadyCompleted && targetPresent && featureEnabled && authorised;
    }

    private void strike(Level level, BlockPos pos, BlockState state) {
        if (completed) {
            return; // already struck; a reload replaying the last tick changes nothing
        }
        completed = true;
        setChanged();

        LivingEntity target = pendingTarget == null ? null
                : asLiving(TetherService.find(level.getServer(), pendingTarget));
        UUID targetId = pendingTarget;
        pendingTarget = null;
        BlockPos device = GuillotineBlock.devicePos(pos);
        if (!strikes(false, target != null && target.isAlive(), guillotineEnabled(),
                ExecutionAuthorization.authorised(targetId, level.dimension().location(), device,
                        level.getGameTime()))) {
            // No order, or one that was cleared inside the window by a pardon, a rescue or a death.
            // The blade has fallen and nobody has died, which is exactly what is supposed to happen.
            return;
        }
        Entity actor = pendingActor == null ? null : TetherService.find(level.getServer(), pendingActor);
        DamageSource source = TetherDamage.hang(level, actor);
        if (source == null) {
            return;
        }
        float lethal = target.getMaxHealth() + target.getAbsorptionAmount() + 1.0F;
        boolean hurt = target.hurt(source, lethal);
        boolean dead = hurt && !target.isAlive();
        if (!dead) {
            // Cancelled by a totem, a revive mod, invulnerability or another mod's damage handler.
            // Nothing resolves: no head, no bounty, no property recovery, and the order stands until
            // it is cleared by one of the seven rules.
            bloody = bloody || hurt;
            apply(level, pos, state);
            return;
        }
        bloody = true;
        ExecutionAuthorization.clear(targetId, ExecutionAuthorization.ClearReason.CARRIED_OUT);
        dropHead(level, target);
        CustodyTransitionService.onExecuted(level.getServer(), target, actor);
        apply(level, pos, state);
    }

    /** A player's head, once, and nothing at all for anybody else. */
    private void dropHead(Level level, LivingEntity target) {
        if (!dropsHead() || !(target instanceof Player player)) {
            return;
        }
        ItemStack head = new ItemStack(Items.PLAYER_HEAD);
        // 1.21 has no item NBT: a skull's owner is the profile data component, and the whole
        // GameProfile is carried rather than a name string, so the head resolves without a lookup.
        head.set(net.minecraft.core.component.DataComponents.PROFILE,
                new net.minecraft.world.item.component.ResolvableProfile(player.getGameProfile()));
        level.addFreshEntity(new net.minecraft.world.entity.item.ItemEntity(level,
                target.getX(), target.getY() + 0.5D, target.getZ(), head));
    }

    @Nullable
    private LivingEntity occupant(Level level, BlockPos pos) {
        CrimeWorldData data = PilloryBlock.data(level);
        BlockPos device = GuillotineBlock.devicePos(pos);
        DetentionRecord record = DetentionService.at(data, level.dimension().location(), device)
                .orElse(null);
        if (record == null) {
            return null;
        }
        return asLiving(TetherService.find(level.getServer(), record.subject()));
    }

    @Nullable
    private static LivingEntity asLiving(@Nullable Entity entity) {
        return entity instanceof LivingEntity living ? living : null;
    }

    private static int activationDelayTicks() {
        try {
            return McaCrimeConfig.COMMON.guillotineActivationDelayTicks.get();
        } catch (IllegalStateException | NullPointerException notLoaded) {
            return 5;
        }
    }

    private static boolean guillotineEnabled() {
        try {
            return McaCrimeConfig.COMMON.guillotineEnabled.get();
        } catch (IllegalStateException | NullPointerException notLoaded) {
            return true;
        }
    }

    private static boolean dropsHead() {
        try {
            return McaCrimeConfig.COMMON.guillotineDropsHead.get();
        } catch (IllegalStateException | NullPointerException notLoaded) {
            return true;
        }
    }

    /** Writes the visual state back onto the block, so the model matches the device. */
    private void apply(Level level, BlockPos pos, BlockState state) {
        setChanged();
        if (state.getBlock() instanceof GuillotineBlock) {
            BlockState next = state
                    .setValue(GuillotineBlock.BLADE_DOWN, bladeDown)
                    .setValue(GuillotineBlock.BLOODY, bloody);
            if (next != state) {
                level.setBlock(pos, next, Block.UPDATE_ALL);
            }
        }
        level.sendBlockUpdated(pos, getBlockState(), getBlockState(), Block.UPDATE_ALL);
    }

    public boolean bladeDown() {
        return bladeDown;
    }

    public boolean bloody() {
        return bloody;
    }

    /** Ticks left before the blow. Exposed for {@code /crime debug} and the tests. */
    public int chopDelay() {
        return chopDelay;
    }

    public boolean completed() {
        return completed;
    }

    // --- persistence -------------------------------------------------------------------------------------

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.putBoolean(TAG_BLADE_DOWN, bladeDown);
        tag.putBoolean(TAG_BLOODY, bloody);
        tag.putInt(TAG_DELAY, chopDelay);
        tag.putBoolean(TAG_DONE, completed);
        if (pendingTarget != null) {
            tag.putUUID(TAG_TARGET, pendingTarget);
        }
        if (pendingActor != null) {
            tag.putUUID(TAG_ACTOR, pendingActor);
        }
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        if (tag == null) {
            return;
        }
        bladeDown = tag.getBoolean(TAG_BLADE_DOWN);
        bloody = tag.getBoolean(TAG_BLOODY);
        chopDelay = Math.max(0, tag.getInt(TAG_DELAY));
        completed = tag.getBoolean(TAG_DONE);
        pendingTarget = tag.hasUUID(TAG_TARGET) ? tag.getUUID(TAG_TARGET) : null;
        pendingActor = tag.hasUUID(TAG_ACTOR) ? tag.getUUID(TAG_ACTOR) : null;
    }

    @Override
    @Nullable
    public Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        return saveWithoutMetadata(registries);
    }

    /**
     * The chunk is going away with an order armed at this device.
     *
     * <p>§3.19's seventh clearing rule. The condemned stay condemned and in custody; nothing about
     * the sentence changes, and the order simply has to be given again.
     */
    @Override
    public void setRemoved() {
        if (level != null && !level.isClientSide()) {
            ExecutionAuthorization.clearAt(level.dimension().location(),
                    GuillotineBlock.devicePos(getBlockPos()),
                    ExecutionAuthorization.ClearReason.CHUNK_UNLOADED);
        }
        super.setRemoved();
    }
}
