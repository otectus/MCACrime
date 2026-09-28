package dev.otectus.mcacrime.menu;

import dev.otectus.mcacrime.frisk.FriskSession;
import dev.otectus.mcacrime.frisk.FriskSlotRef;
import dev.otectus.mcacrime.frisk.InventoryProviders;
import net.minecraft.world.Container;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.function.Supplier;

/**
 * A read-only window onto somebody else's pockets (M5.2, plan §3.8).
 *
 * <p>Every mutating method of {@link Container} is implemented as a no-op that returns
 * {@code ItemStack.EMPTY}. That is deliberate and it is the fix for the upstream container, which
 * implements the same interface and actually does the mutations — {@code removeItem} moves the whole
 * stack regardless of the requested count, {@code removeItemNoUpdate} does the same, and
 * {@code clearContent} empties the subject's entire inventory
 * ({@code inventory/FriskingContainer.java:69-102,140-142}). Vanilla menu machinery calls all three
 * on paths a client can reach.
 *
 * <p>{@link #stillValid} is a real question here, asked of the live session every tick. Upstream's
 * returns the literal {@code true}.
 */
public final class FriskProjection implements Container {

    private final FriskSession session;
    private final Supplier<LivingEntity> subject;

    public FriskProjection(FriskSession session, Supplier<LivingEntity> subject) {
        this.session = session;
        this.subject = subject;
    }

    @Nullable
    private LivingEntity live() {
        return subject == null ? null : subject.get();
    }

    @Override
    public int getContainerSize() {
        return session.view().size();
    }

    @Override
    public boolean isEmpty() {
        for (int i = 0; i < getContainerSize(); i++) {
            if (!getItem(i).isEmpty()) {
                return false;
            }
        }
        return true;
    }

    @Override
    @Nonnull
    public ItemStack getItem(int index) {
        FriskSlotRef ref = session.slotAt(index);
        return ref == null ? ItemStack.EMPTY : InventoryProviders.peek(live(), ref);
    }

    /** No. A transfer is a packet and a transaction, never a container call. */
    @Override
    @Nonnull
    public ItemStack removeItem(int index, int count) {
        return ItemStack.EMPTY;
    }

    /** No, for the same reason. */
    @Override
    @Nonnull
    public ItemStack removeItemNoUpdate(int index) {
        return ItemStack.EMPTY;
    }

    /** No. Nothing may be placed into somebody else's inventory through a search screen. */
    @Override
    public void setItem(int index, @Nonnull ItemStack stack) {
    }

    /**
     * Emphatically no.
     *
     * <p>This is the method upstream uses to empty the subject's inventory. Vanilla calls it from
     * {@code AbstractContainerMenu} paths, so leaving it implemented would hand every client a
     * one-click delete of somebody else's possessions.
     */
    @Override
    public void clearContent() {
    }

    @Override
    public void setChanged() {
    }

    @Override
    public boolean stillValid(@Nonnull Player player) {
        return player instanceof net.minecraft.server.level.ServerPlayer searcher
                && dev.otectus.mcacrime.frisk.FriskingService.stillValid(searcher, session);
    }

    @Override
    public boolean canPlaceItem(int index, @Nonnull ItemStack stack) {
        return false;
    }

    @Override
    public boolean canTakeItem(@Nonnull Container target, int index, @Nonnull ItemStack stack) {
        return false;
    }
}
