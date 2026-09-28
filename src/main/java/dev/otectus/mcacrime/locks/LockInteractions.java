package dev.otectus.mcacrime.locks;

import dev.otectus.mcacrime.McaCrimeConfig;
import dev.otectus.mcacrime.audio.CrimeSounds;
import dev.otectus.mcacrime.item.CrimeItems;
import dev.otectus.mcacrime.item.creative.BindBreakerItem;
import dev.otectus.mcacrime.item.creative.CreativeAuthorization;
import dev.otectus.mcacrime.item.creative.CreativeKeyItem;
import dev.otectus.mcacrime.item.lock.KeyItem;
import dev.otectus.mcacrime.item.lock.KeyRingItem;
import dev.otectus.mcacrime.item.lock.LockpickItem;
import dev.otectus.mcacrime.lockpick.LockpickService;
import dev.otectus.mcacrime.state.world.CrimeWorldData;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

import org.jetbrains.annotations.Nullable;
import java.util.Optional;
import java.util.UUID;

/**
 * One routing table for every "player used something on a lock" interaction (M3.1, M3.2, M3.4).
 *
 * <p>A cell door, a safe and a padlock all answer the same five questions in the same order, so they
 * ask them in one place rather than three: bind a blank key, break a binding, open with a master key,
 * turn a bound key, or start picking. Upstream writes the same chain out three times with three
 * different orderings and three different sets of missing checks.
 *
 * <p>Everything here runs on the server. The actor is the connection's player, the lock is resolved
 * from the store, and the outcome is computed here — never accepted from a packet.
 */
public final class LockInteractions {

    private LockInteractions() {
    }

    /** What an interaction with a lock turned out to be. */
    public enum Route {
        /** A blank key or a ring with room, on a holder nobody has bound yet. */
        BIND,
        /** An operator rotating the binding revision, invalidating every old key. */
        BREAK_BINDING,
        /** A current key, a ring holding one, or an authorised master key. */
        TOGGLE,
        /** A pick, on something that is locked. */
        PICK,
        /** Nothing about this interaction is about the lock. */
        PASS
    }

    /**
     * Which route this interaction takes, decided from item identity and lock state alone.
     *
     * <p>Pure, so the order can be asserted: a bind breaker beats a key, a key beats a pick, and a
     * pick is only ever considered on a bound holder. Authority is an input rather than something
     * looked up here, because "may this player use an operator item" is a permission question the
     * server answers once and hands in.
     */
    public static Route route(boolean bindBreaker, boolean masterKey, boolean boundKeyMatches,
                              boolean blankKey, boolean lockpick, boolean holderBound,
                              boolean operatorAuthorised) {
        if (!holderBound) {
            return blankKey ? Route.BIND : Route.PASS;
        }
        if (bindBreaker) {
            return operatorAuthorised ? Route.BREAK_BINDING : Route.PASS;
        }
        if (masterKey && operatorAuthorised) {
            return Route.TOGGLE;
        }
        if (boundKeyMatches) {
            return Route.TOGGLE;
        }
        if (lockpick) {
            return Route.PICK;
        }
        return Route.PASS;
    }

    // --- item identity ------------------------------------------------------------------------------

    public static boolean isKey(@Nullable ItemStack stack) {
        return stack != null && stack.getItem() instanceof KeyItem;
    }

    public static boolean isKeyRing(@Nullable ItemStack stack) {
        return stack != null && stack.getItem() instanceof KeyRingItem;
    }

    public static boolean isLockpick(@Nullable ItemStack stack) {
        return stack != null && !stack.isEmpty()
                && (stack.getItem() instanceof LockpickItem || stack.is(LockTags.LOCKPICKS));
    }

    public static boolean isMasterKey(@Nullable ItemStack stack) {
        return stack != null && stack.getItem() instanceof CreativeKeyItem;
    }

    public static boolean isBindBreaker(@Nullable ItemStack stack) {
        return stack != null && stack.getItem() instanceof BindBreakerItem;
    }

    /** A key with no binding, or a ring with room for one more. */
    public static boolean isBlankKey(@Nullable ItemStack stack) {
        if (isKey(stack)) {
            return !KeyBinding.bound(stack);
        }
        if (isKeyRing(stack)) {
            return KeyRingBindings.hasRoom(stack, maxKeysPerRing());
        }
        return false;
    }

    /** The binding this stack presents for {@code lock}: from a key, or from a ring. */
    public static Optional<KeyBinding> presented(@Nullable ItemStack stack, @Nullable LockRecord lock) {
        if (stack == null || stack.isEmpty() || lock == null) {
            return Optional.empty();
        }
        if (isKey(stack)) {
            return KeyBinding.read(stack).filter(binding -> binding.opens(lock));
        }
        if (isKeyRing(stack)) {
            return KeyRingBindings.opening(stack, lock);
        }
        return Optional.empty();
    }

    /** The configured ring capacity. */
    public static int maxKeysPerRing() {
        try {
            return KeyRingBindings.clampCapacity(McaCrimeConfig.COMMON.maxKeysPerRing.get());
        } catch (IllegalStateException notLoaded) {
            return 16;
        }
    }

    // --- the interaction ----------------------------------------------------------------------------

    /**
     * Runs one interaction against a lock holder.
     *
     * <p>Returns {@link InteractionResult#PASS} when the interaction was not about the lock, so a
     * player right-clicking a safe with a stack of cobblestone still gets the safe's own behaviour.
     */
    public static InteractionResult interact(@Nullable ServerPlayer player, @Nullable Level level,
                                             @Nullable ItemStack stack, @Nullable LockHolder holder,
                                             @Nullable BlockPos soundPos) {
        if (player == null || level == null || level.isClientSide() || stack == null || holder == null) {
            return InteractionResult.PASS;
        }
        CrimeWorldData data = LockService.data(level);
        if (data == null) {
            return InteractionResult.PASS;
        }
        LockRecord lock = holder.lockId().map(data::lock).orElse(null);
        boolean operator = CreativeAuthorization.permits(player);
        Route route = route(isBindBreaker(stack), isMasterKey(stack),
                presented(stack, lock).isPresent(), isBlankKey(stack), isLockpick(stack),
                lock != null, operator);
        return switch (route) {
            case BIND -> bind(player, data, stack, holder, soundPos);
            case BREAK_BINDING -> breakBinding(player, data, holder, lock);
            case TOGGLE -> toggle(player, level, data, holder, lock, soundPos);
            case PICK -> LockpickService.begin(player, holder, lock);
            case PASS -> refuse(player, lock, stack);
        };
    }

    /**
     * Binds a blank key, creating the lock on first use.
     *
     * <p>The lock record is created here rather than when the block is placed, so an unbound cell door
     * is an ordinary door and the {@code locks} table carries no row for every door in a village that
     * nobody has locked.
     */
    private static InteractionResult bind(ServerPlayer player, CrimeWorldData data, ItemStack stack,
                                          LockHolder holder, @Nullable BlockPos soundPos) {
        LockTarget target = holder.lockTarget();
        if (!target.present()) {
            return InteractionResult.PASS;
        }
        if (ForeignLockPolicy.configured() == ForeignLockPolicy.REFUSE
                && dev.otectus.mcacrime.compat.LocksReforgedBridge.ownsLock(player.level(), target)) {
            player.displayClientMessage(Component.translatable("mcacrime.lock.foreign_owner"), true);
            return InteractionResult.FAIL;
        }
        Optional<LockRecord> created = LockService.getOrCreate(data, target, player.getUUID());
        if (created.isEmpty()) {
            return InteractionResult.FAIL;
        }
        LockRecord lock = created.get();
        if (!holder.bindLock(lock.lockId())) {
            return InteractionResult.FAIL;
        }
        String name = holder.lockDisplayName().getString();
        KeyBinding binding = KeyBinding.forLock(lock, name);
        if (isKeyRing(stack)) {
            if (!KeyRingBindings.add(stack, binding, maxKeysPerRing())) {
                return InteractionResult.FAIL;
            }
        } else {
            binding.writeTo(stack);
        }
        holder.onLockChanged();
        CrimeSounds.keyBound(player);
        player.displayClientMessage(Component.translatable("mcacrime.lock.bound",
                holder.lockDisplayName()), true);
        return InteractionResult.SUCCESS;
    }

    /** The creative bind breaker: a real rekey, so every copy of the old key stops working. */
    private static InteractionResult breakBinding(ServerPlayer player, CrimeWorldData data,
                                                  LockHolder holder, @Nullable LockRecord lock) {
        if (lock == null) {
            return InteractionResult.PASS;
        }
        if (LockService.rekey(data, lock.lockId()).isEmpty()) {
            return InteractionResult.FAIL;
        }
        holder.onLockChanged();
        player.displayClientMessage(Component.translatable("mcacrime.lock.rekeyed_by_operator",
                holder.lockDisplayName()), true);
        return InteractionResult.SUCCESS;
    }

    /** Turns the key: locked becomes unlocked and back. */
    private static InteractionResult toggle(ServerPlayer player, Level level, CrimeWorldData data,
                                            LockHolder holder, @Nullable LockRecord lock,
                                            @Nullable BlockPos soundPos) {
        if (lock == null) {
            return InteractionResult.PASS;
        }
        boolean locking = !lock.locked();
        if (LockService.setLocked(data, lock.lockId(), locking).isEmpty()) {
            return InteractionResult.FAIL;
        }
        holder.onLockChanged();
        CrimeSounds.lockToggled(level, soundPos == null ? player.blockPosition() : soundPos, locking);
        player.displayClientMessage(Component.translatable(
                locking ? "mcacrime.lock.locked_now" : "mcacrime.lock.unlocked_now",
                holder.lockDisplayName()), true);
        return InteractionResult.SUCCESS;
    }

    /**
     * Says why nothing happened, when the player was clearly trying.
     *
     * <p>Only for a key: somebody holding a pickaxe gets the block's ordinary behaviour and no
     * message, and somebody holding the wrong key deserves to be told which kind of wrong it is.
     */
    private static InteractionResult refuse(ServerPlayer player, @Nullable LockRecord lock,
                                            ItemStack stack) {
        if (lock == null || !(isKey(stack) || isKeyRing(stack))) {
            return InteractionResult.PASS;
        }
        LockAccess.Decision decision = LockAccess.evaluate(lock,
                isKey(stack) ? KeyBinding.read(stack).orElse(null)
                        : KeyRingBindings.find(stack, lock.lockId()).orElse(null),
                false);
        player.displayClientMessage(Component.translatable(LockAccess.messageKey(decision)), true);
        return InteractionResult.FAIL;
    }

    /**
     * The lock-holding block entity at {@code pos}, with a door's upper half resolved to its lower.
     *
     * <p>Doors are the case that needs it: only the lower half carries the handle, and a player
     * clicking the top of a cell door is working the same lock.
     */
    @Nullable
    public static LockHolder holderAt(@Nullable Level level, @Nullable BlockPos pos) {
        if (level == null || pos == null) {
            return null;
        }
        LockHolder own = null;
        BlockPos lower = pos;
        if (level.getBlockEntity(pos) instanceof LockHolder holder) {
            own = holder;
        } else {
            net.minecraft.world.level.block.state.BlockState state = level.getBlockState(pos);
            if (state.getBlock() instanceof net.minecraft.world.level.block.DoorBlock
                    && state.hasProperty(net.minecraft.world.level.block.DoorBlock.HALF)
                    && state.getValue(net.minecraft.world.level.block.DoorBlock.HALF)
                            == net.minecraft.world.level.block.state.properties.DoubleBlockHalf.UPPER
                    && level.getBlockEntity(pos.below()) instanceof LockHolder below) {
                own = below;
                lower = pos.below();
            }
        }
        if (own == null) {
            return null;
        }
        // A padlock hanging on a cell door that nobody has bound a key to answers for that door. The
        // padlock's lock already targets the door's canonical position, so the position-based
        // protection checks see it; this is what lets the interaction see it too, so a key or a pick
        // used on the door from either side works the padlock rather than an empty handle. That is
        // how a prisoner inside a generated cell, who cannot reach the padlock on the outside face,
        // still picks it.
        if (own.lockId().isPresent()
                || !(level.getBlockState(lower).getBlock() instanceof net.minecraft.world.level.block.DoorBlock)) {
            return own;
        }
        LockHolder padlock = padlockOnDoor(level, lower);
        return padlock == null ? own : padlock;
    }

    /** The padlock entity hanging on either half of the door whose lower half is {@code lower}. */
    @Nullable
    public static dev.otectus.mcacrime.entity.PadlockEntity padlockOnDoor(@Nullable Level level,
                                                                          @Nullable BlockPos lower) {
        if (level == null || lower == null) {
            return null;
        }
        BlockPos upper = lower.above();
        net.minecraft.world.phys.AABB around = new net.minecraft.world.phys.AABB(lower)
                .expandTowards(0.0D, 1.0D, 0.0D).inflate(1.0D);
        for (dev.otectus.mcacrime.entity.PadlockEntity padlock : level.getEntitiesOfClass(
                dev.otectus.mcacrime.entity.PadlockEntity.class, around,
                candidate -> candidate.isAlive()
                        && (lower.equals(candidate.getPos()) || upper.equals(candidate.getPos())))) {
            return padlock;
        }
        return null;
    }

    /**
     * The item-side entry point, for the click that never reaches the block's own {@code use}.
     *
     * <p>A crouching player holding a key skips {@code BlockState.use} entirely, so a key that only
     * worked through the block would stop working the moment its owner crouched. Both routes end in
     * the same {@link #interact}.
     */
    public static InteractionResult useOnBlock(@Nullable net.minecraft.world.item.context.UseOnContext ctx) {
        if (ctx == null || ctx.getLevel().isClientSide()
                || !(ctx.getPlayer() instanceof ServerPlayer player)) {
            return InteractionResult.PASS;
        }
        LockHolder holder = holderAt(ctx.getLevel(), ctx.getClickedPos());
        if (holder == null) {
            return InteractionResult.PASS;
        }
        return interact(player, ctx.getLevel(), ctx.getItemInHand(), holder, ctx.getClickedPos());
    }

    /** Whether {@code player} may pass this lock right now, with whatever they are holding. */
    public static boolean mayPass(@Nullable ServerPlayer player, @Nullable LockRecord lock) {
        if (lock == null || !lock.locked()) {
            return true;
        }
        if (player == null) {
            return false;
        }
        if (CreativeAuthorization.permits(player) && isMasterKey(player.getMainHandItem())) {
            return true;
        }
        for (ItemStack stack : player.getInventory().items) {
            if (presented(stack, lock).isPresent()) {
                return true;
            }
        }
        return false;
    }

    /** The owner of a lock, when a player set it. */
    public static Optional<UUID> owner(@Nullable LockRecord lock) {
        return lock == null ? Optional.empty() : lock.ownerId();
    }

    /** Whether the held stack is one of this mod's keys at all, for a router that has to choose. */
    public static boolean anyKey(@Nullable ItemStack stack) {
        return isKey(stack) || isKeyRing(stack) || isMasterKey(stack);
    }

    /** The key item, so callers do not have to name the registry object. */
    public static ItemStack blankKey() {
        return new ItemStack(CrimeItems.KEY.get());
    }
}
