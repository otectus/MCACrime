package dev.otectus.mcacrime.compat.locksreforged;

import dev.otectus.mcacrime.McaCrimeConfig;
import dev.otectus.mcacrime.captivity.CuffEscapeService;
import dev.otectus.mcacrime.captivity.CuffLockProgress;
import dev.otectus.mcacrime.captivity.CustodyRecord;
import dev.otectus.mcacrime.captivity.RestraintType;
import dev.otectus.mcacrime.enforcement.RestraintPolicy;
import dev.otectus.mcacrime.state.world.CrimeWorldData;
import melonslise.locks.common.container.LockPickingContainer;
import melonslise.locks.common.container.LockPickingMode;
import melonslise.locks.common.init.LocksNetwork;
import melonslise.locks.common.init.LocksSoundEvents;
import melonslise.locks.common.init.LocksTagHelper;
import melonslise.locks.common.network.toclient.TryPinResultPacket;
import melonslise.locks.common.util.Cuboid6i;
import melonslise.locks.common.util.Lock;
import melonslise.locks.common.util.Lockable;
import melonslise.locks.common.util.Transform;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.network.NetworkHooks;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.registries.ForgeRegistries;

import java.lang.reflect.Constructor;
import java.security.SecureRandom;

/**
 * Server menu using Locks' own client screen and pin packets. The lock exists only in this menu:
 * it is never registered as a block lock or placed in the world. Only solving its secret releases custody.
 * Kept behind LocksReforgedBridge so servers without Locks never resolve these optional types.
 */
public final class CuffLockPickingMenu extends LockPickingContainer {
    private static final SecureRandom RANDOM = new SecureRandom();
    private final CustodyRecord custody;
    private final ResourceLocation openedDimension;
    private final RestraintType restraint;
    private final CuffLockProgress progress = new CuffLockProgress();
    private final Constructor<?> resultConstructor;
    private final boolean sequenced;
    private Object previousResult;

    private CuffLockPickingMenu(int id, ServerPlayer player, CustodyRecord custody, Lockable lock,
            Constructor<?> resultConstructor, boolean sequenced) {
        super(id, player, InteractionHand.MAIN_HAND, LockPickingMode.ITEMLESS, lock);
        this.custody = custody;
        this.openedDimension = player.level().dimension().location();
        this.restraint = RestraintPolicy.effective(player).orElse(RestraintType.NONE);
        this.resultConstructor = resultConstructor;
        this.sequenced = sequenced;
    }

    public static boolean hasPick(Player player) {
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            ItemStack stack = player.getInventory().getItem(i);
            if (!stack.isEmpty() && LocksTagHelper.isLockPick(stack)) return true;
        }
        return false;
    }

    public static boolean open(ServerPlayer player, CustodyRecord custody) throws ReflectiveOperationException {
        RestraintType restraint = RestraintPolicy.effective(player).orElse(RestraintType.NONE);
        boolean hasPick = hasPick(player);
        if (!CuffEscapeService.valid(player, custody, player.level().dimension().location(), restraint, hasPick)) {
            if (McaCrimeConfig.COMMON.cuffEscapeRequiresLockpick.get() && !hasPick)
                player.sendSystemMessage(Component.translatable("mcacrime.captive.escape.pick_required"));
            return false;
        }
        // Bind before opening: an incompatible Locks version must fail without opening a dead menu.
        Constructor<?> result;
        boolean sequenced;
        try {
            result = TryPinResultPacket.class.getConstructor(int.class, int.class, int.class, int.class,
                    boolean.class, boolean.class, boolean.class);
            sequenced = true;
        } catch (NoSuchMethodException legacy) {
            result = TryPinResultPacket.class.getConstructor(boolean.class, boolean.class);
            sequenced = false;
        }
        byte[] combination = custody.getCuffCombination();
        if (!CuffLockProgress.validCombination(combination)) {
            combination = new byte[restraint == RestraintType.LOCKED_CUFFS ? 7 : 5];
            for (int i = 0; i < combination.length; i++) combination[i] = (byte) i;
            for (int i = combination.length - 1; i > 0; i--) {
                int j = RANDOM.nextInt(i + 1);
                byte swap = combination[i]; combination[i] = combination[j]; combination[j] = swap;
            }
            custody.setCuffCombination(combination);
            CrimeWorldData.get(player.getServer()).setDirty();
        }
        // Credential does not seed the secret. Negative menu-only ids cannot alias ordinary block locks.
        int lockId = -1 - RANDOM.nextInt(Integer.MAX_VALUE);
        Lock lock;
        try {
            lock = (Lock) Lock.class.getMethod("restore", int.class, byte[].class, boolean.class)
                    .invoke(null, lockId, combination, true);
        } catch (NoSuchMethodException legacy) {
            lock = Lock.class.getConstructor(int.class, byte[].class, boolean.class)
                    .newInstance(lockId, combination, true);
        }
        var item = ForgeRegistries.ITEMS.getValue(new ResourceLocation("locks", "iron_lock"));
        if (item == null) throw new IllegalStateException("Locks Reforged iron_lock is unavailable");
        Lockable target = new Lockable(new Cuboid6i(player.blockPosition(), player.blockPosition()), lock,
                Transform.NORTH_MID, new ItemStack(item), lockId);
        Constructor<?> boundResult = result;
        boolean boundSequenced = sequenced;
        NetworkHooks.openScreen(player, new SimpleMenuProvider((id, inventory, actor) ->
                new CuffLockPickingMenu(id, player, custody, target, boundResult, boundSequenced),
                Component.translatable("mcacrime.captive.escape.lock_title")),
                new LockPickingContainer.Writer(InteractionHand.MAIN_HAND, LockPickingMode.ITEMLESS, target));
        return player.containerMenu instanceof CuffLockPickingMenu;
    }

    @Override public boolean canAttempt(Player actor) {
        return actor == player && actor instanceof ServerPlayer serverPlayer
                && serverPlayer.containerMenu == this && !progress.completed()
                && CuffEscapeService.valid(serverPlayer, custody, openedDimension, restraint, hasPick(actor));
    }

    @Override public boolean stillValid(Player actor) { return canAttempt(actor); }

    /** Published 1.7.3 / 1.7.4 packet entry point. Never call the block-lock implementation. */
    @Override public void tryPin(int pin) {
        if (!sequenced) attempt(pin, progress.lastSequence() + 1);
    }

    /** Newer Locks protocol; kept as an overload so the published 1.7.3 API remains sufficient to build. */
    public void tryPin(int pin, int menuId, int sequence) {
        if (!sequenced || menuId != containerId) return;
        if (sequence == progress.lastSequence() && previousResult != null && canAttempt(player)) {
            send(previousResult);
            return;
        }
        attempt(pin, sequence);
    }

    private void attempt(int pin, int sequence) {
        if (!canAttempt(player)) {
            ((ServerPlayer) player).closeContainer();
            return;
        }
        int length = lockable.lock.getLength();
        if (!progress.accept(pin, length, sequence, player.level().getGameTime())) {
            ((ServerPlayer) player).closeContainer(); // Avoid leaving the client awaiting a result forever.
            return;
        }
        boolean correct = lockable.lock.checkPin(progress.solved(), pin);
        boolean opened = progress.resolve(correct, length);
        currIndex = progress.solved();
        try {
            previousResult = sequenced
                    ? resultConstructor.newInstance(containerId, sequence, pin, currIndex, correct, !correct, opened)
                    : resultConstructor.newInstance(correct, !correct); // Legacy second flag means reset, including itemless misses.
            send(previousResult);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Locks Reforged pin-result binding changed", e);
        }
        player.level().playSound(null, player.blockPosition(),
                correct ? LocksSoundEvents.PIN_MATCH.get() : LocksSoundEvents.PIN_FAIL.get(),
                SoundSource.PLAYERS, 1f, 1f);
        if (opened) CuffEscapeService.completed((ServerPlayer) player, custody);
    }

    private void send(Object result) {
        LocksNetwork.MAIN.send(PacketDistributor.PLAYER.with(() -> (ServerPlayer) player), result);
    }

    // Hidden native inventory slots are for display sync only. Cuffs never consume or move a pick.
    @Override public void clicked(int slot, int button, ClickType type, Player actor) {}

    @Override public void removed(Player actor) {
        // Older Locks commits block unlocks in removed(). A closed cuff screen must do nothing.
        previousResult = null;
    }
}
