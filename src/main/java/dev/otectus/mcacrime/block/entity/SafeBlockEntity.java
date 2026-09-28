package dev.otectus.mcacrime.block.entity;

import dev.otectus.mcacrime.audio.CrimeSounds;
import dev.otectus.mcacrime.block.SafeBlock;
import dev.otectus.mcacrime.locks.LockAutomationPolicy;
import dev.otectus.mcacrime.locks.LockRecord;
import dev.otectus.mcacrime.locks.LockService;
import dev.otectus.mcacrime.locks.LockTarget;
import dev.otectus.mcacrime.locks.LockTargetNormalizer;
import dev.otectus.mcacrime.state.world.CrimeWorldData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.NonNullList;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.ContainerOpenersCounter;
import net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.items.IItemHandler;

import org.jetbrains.annotations.Nullable;
import java.util.Optional;
import java.util.UUID;

/**
 * The safe: a locked container that owns <em>both</em> its menu access and its item handler (M3.5).
 *
 * <p>Two access routes, one authority. A player opening the menu is checked by
 * {@link #canOpen}; a hopper, a hopper minecart or any mod holding an {@link IItemHandler} is checked
 * by {@link LockAwareItemHandler} on <b>every operation</b>. That second half is the one upstream does
 * not have at all — there is no item handler anywhere in its source, so its safe is protected against
 * right-clicks and against nothing else.
 *
 * <p>Cached handlers are dropped whenever the lock changes, so a handler somebody obtained while the
 * safe stood open stops resolving. On this line that is {@code Level.invalidateCapabilities(pos)}
 * rather than a {@code LazyOptional} the block entity owns (plan §7.1 item 4). Both halves exist on
 * purpose: invalidation is the fast path, and the per-operation check is the one that cannot be
 * defeated by a reference held across the invalidation.
 *
 * <p>Contents outlive the lock. Breaking the block drops everything once (the block's {@code onRemove}),
 * a destructive pick moves everything out before the block goes, and lowering {@code prison.safeSlots}
 * keeps the surplus (see {@link SafeInventoryPolicy}).
 */
public class SafeBlockEntity extends RandomizableContainerBlockEntity
        implements dev.otectus.mcacrime.locks.LockHolder {

    private static final String TAG_LOCK_ID = "LockId";

    private NonNullList<ItemStack> items =
            NonNullList.withSize(SafeInventoryPolicy.DEFAULT_SLOTS, ItemStack.EMPTY);
    @Nullable
    private UUID lockId;

    private final ContainerOpenersCounter openersCounter = new ContainerOpenersCounter() {
        @Override
        protected void onOpen(Level level, BlockPos pos, BlockState state) {
            CrimeSounds.safeOpened(level, pos);
            level.setBlock(pos, state.setValue(SafeBlock.OPEN, Boolean.TRUE), 3);
        }

        @Override
        protected void onClose(Level level, BlockPos pos, BlockState state) {
            CrimeSounds.safeClosed(level, pos);
            level.setBlock(pos, state.setValue(SafeBlock.OPEN, Boolean.FALSE), 3);
        }

        @Override
        protected void openerCountChanged(Level level, BlockPos pos, BlockState state, int was, int now) {
            // Nothing beyond the door animation: a safe has no comparator behaviour of its own.
        }

        @Override
        protected boolean isOwnContainer(Player player) {
            return player.containerMenu instanceof ChestMenu menu
                    && menu.getContainer() == SafeBlockEntity.this;
        }
    };

    public SafeBlockEntity(BlockPos pos, BlockState state) {
        super(CrimeBlockEntities.SAFE.get(), pos, state);
    }

    // --- the lock handle ---------------------------------------------------------------------------

    @Override
    public Optional<UUID> lockId() {
        return Optional.ofNullable(lockId);
    }

    @Override
    public LockTarget lockTarget() {
        return level == null ? LockTarget.none()
                : LockTargetNormalizer.forBlock(level, level.dimension().location(), worldPosition);
    }

    @Override
    public Component lockDisplayName() {
        return getDefaultName();
    }

    /** The safe's own profile: the hardest of the four block profiles to pick (spec §8). */
    @Override
    public dev.otectus.mcacrime.lockpick.LockpickProfile pickProfile(boolean reinforced) {
        return dev.otectus.mcacrime.lockpick.LockpickProfile.SAFE;
    }

    public boolean bound() {
        return lockId != null;
    }

    /** Binds this safe to a lock. Never replaces a different one. */
    @Override
    public boolean bindLock(@Nullable UUID id) {
        if (id == null || (lockId != null && !lockId.equals(id))) {
            return false;
        }
        if (!id.equals(lockId)) {
            lockId = id;
            setChanged();
        }
        return true;
    }

    public void unbind() {
        if (lockId != null) {
            lockId = null;
            setChanged();
            onLockChanged();
        }
    }

    public Optional<LockRecord> lock(@Nullable CrimeWorldData data) {
        return LockService.byId(data, lockId);
    }

    /** Whether this safe is locked right now, read from the store rather than from a local flag. */
    public boolean locked() {
        CrimeWorldData data = LockService.data(level);
        return lock(data).map(LockRecord::locked).orElse(false);
    }

    /**
     * Called after anything changes about this safe's lock.
     *
     * <p>Drops every cached capability at this position so a handler nobody should still have stops
     * resolving, and turns out anybody whose menu is open on a safe that has just been locked.
     * Revalidation rather than a silent continuation: the alternative is a player with the screen
     * already open emptying a safe that the owner just locked in front of them.
     */
    @Override
    public void onLockChanged() {
        invalidateHandler();
        if (level == null || level.isClientSide() || !locked()) {
            return;
        }
        for (Player player : level.players()) {
            if (player instanceof ServerPlayer server && server.containerMenu instanceof ChestMenu menu
                    && menu.getContainer() == this) {
                server.closeContainer();
            }
        }
    }

    private void invalidateHandler() {
        if (level != null && !level.isClientSide()) {
            invalidateCapabilities();
        }
    }

    // --- container ----------------------------------------------------------------------------------

    @Override
    public int getContainerSize() {
        return items.size();
    }

    /** How many slots the menu shows. Overflow above this is carried, not shown and not lost. */
    public int menuSlots() {
        return SafeInventoryPolicy.menuSlots(getContainerSize());
    }

    @Override
    protected NonNullList<ItemStack> getItems() {
        return items;
    }

    @Override
    protected void setItems(NonNullList<ItemStack> replacement) {
        this.items = replacement == null
                ? NonNullList.withSize(SafeInventoryPolicy.configuredSlots(), ItemStack.EMPTY)
                : replacement;
    }

    @Override
    protected Component getDefaultName() {
        return Component.translatable("block.mcacrime.safe");
    }

    @Override
    protected AbstractContainerMenu createMenu(int menuId, Inventory inventory) {
        resize(SafeInventoryPolicy.configuredSlots());
        int rows = SafeInventoryPolicy.rows(menuSlots());
        return new ChestMenu(menuTypeFor(rows), menuId, inventory, this, rows);
    }

    private static MenuType<ChestMenu> menuTypeFor(int rows) {
        return switch (rows) {
            case 1 -> MenuType.GENERIC_9x1;
            case 2 -> MenuType.GENERIC_9x2;
            case 3 -> MenuType.GENERIC_9x3;
            case 5 -> MenuType.GENERIC_9x5;
            case 6 -> MenuType.GENERIC_9x6;
            default -> MenuType.GENERIC_9x4;
        };
    }

    /**
     * Grows or shrinks the backing list to {@code configured}, never below what is stored.
     *
     * <p>Shrinking stops at the last occupied slot. That is the whole "preserve overflow rather than
     * truncate" rule: the surplus stays addressable by the block entity, by the drop path and by a
     * later setting change, and is simply outside the menu.
     */
    public void resize(int configured) {
        int lastOccupied = 0;
        for (int i = 0; i < items.size(); i++) {
            if (!items.get(i).isEmpty()) {
                lastOccupied = i + 1;
            }
        }
        int target = SafeInventoryPolicy.resolvedSize(configured, lastOccupied);
        if (target == items.size()) {
            return;
        }
        NonNullList<ItemStack> replacement = NonNullList.withSize(target, ItemStack.EMPTY);
        for (int i = 0; i < Math.min(target, items.size()); i++) {
            replacement.set(i, items.get(i));
        }
        items = replacement;
        setChanged();
    }

    /**
     * Whether this player may open the safe at all.
     *
     * <p>The menu route's half of the lock. A locked safe refuses; the key that unlocks it is used on
     * the block, not carried into the screen.
     */
    @Override
    public boolean canOpen(Player player) {
        return super.canOpen(player) && !locked();
    }

    @Override
    public void startOpen(Player player) {
        if (!this.remove && !player.isSpectator()) {
            openersCounter.incrementOpeners(player, getLevel(), getBlockPos(), getBlockState());
            // One award per opening, from the server-side open that actually happened (M5.11).
            if (player instanceof net.minecraft.server.level.ServerPlayer opener) {
                dev.otectus.mcacrime.stat.CrimeStats.award(opener,
                        dev.otectus.mcacrime.stat.CrimeStats.OPEN_SAFE);
            }
        }
    }

    @Override
    public void stopOpen(Player player) {
        if (!this.remove && !player.isSpectator()) {
            openersCounter.decrementOpeners(player, getLevel(), getBlockPos(), getBlockState());
        }
    }

    public void recheckOpen() {
        if (!this.remove) {
            openersCounter.recheckOpeners(getLevel(), getBlockPos(), getBlockState());
        }
    }

    @Override
    public void setRemoved() {
        super.setRemoved();
        invalidateHandler();
    }

    /** Whether automation may do this right now, asked fresh every time. */
    public boolean automationPermits(LockAutomationPolicy.Operation operation) {
        return LockAutomationPolicy.configured().permits(locked(), operation);
    }

    // --- persistence --------------------------------------------------------------------------------

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        if (!trySaveLootTable(tag)) {
            ContainerHelper.saveAllItems(tag, items, registries);
        }
        if (lockId != null) {
            tag.putUUID(TAG_LOCK_ID, lockId);
        }
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        items = NonNullList.withSize(SafeInventoryPolicy.configuredSlots(), ItemStack.EMPTY);
        if (!tryLoadLootTable(tag)) {
            // Grow first when the saved list is longer than the configured size: the surplus is
            // somebody's property, and a load is not a licence to drop it.
            int saved = tag != null && tag.contains("Items") ? tag.getList("Items", Tag.TAG_COMPOUND).size() : 0;
            int highest = 0;
            if (tag != null && tag.contains("Items")) {
                ListTag list = tag.getList("Items", Tag.TAG_COMPOUND);
                for (int i = 0; i < list.size(); i++) {
                    highest = Math.max(highest, list.getCompound(i).getByte("Slot") & 255);
                }
            }
            int needed = Math.max(saved, highest + 1);
            if (needed > items.size()) {
                items = NonNullList.withSize(needed, ItemStack.EMPTY);
            }
            ContainerHelper.loadAllItems(tag, items, registries);
        }
        lockId = tag != null && tag.hasUUID(TAG_LOCK_ID) ? tag.getUUID(TAG_LOCK_ID) : null;
    }
}
