package dev.otectus.mcacrime.block.entity;

import dev.otectus.mcacrime.locks.LockAutomationPolicy;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.items.IItemHandlerModifiable;
import net.minecraftforge.items.wrapper.InvWrapper;

import javax.annotation.Nonnull;

/**
 * The safe's item handler, which asks the lock on every single operation (spec §10.3).
 *
 * <p>The rule is stated in the specification as plainly as it can be: "every cached handler must
 * re-evaluate current lock state; toggling the lock cannot leave a previously obtained unrestricted
 * handler usable". So there is no captured boolean here and no cached decision — each
 * {@code insertItem} and {@code extractItem} asks {@link SafeBlockEntity#automationPermits} afresh,
 * and a reference somebody obtained an hour ago through a pipe network answers the same as a new one.
 *
 * <p>Insertion and extraction are separate questions, because {@link LockAutomationPolicy#ALLOW_INSERT}
 * is a real configuration: a locked drop box that anything can feed and nothing can empty.
 *
 * <p>{@code setStackInSlot} is refused outright while locked. It is the modifiable escape hatch that
 * bypasses both of the other two, and nothing legitimate needs it on somebody else's locked safe.
 */
public final class LockAwareItemHandler extends InvWrapper implements IItemHandlerModifiable {

    private final SafeBlockEntity safe;

    public LockAwareItemHandler(SafeBlockEntity safe) {
        super(safe);
        this.safe = safe;
    }

    @Nonnull
    @Override
    public ItemStack insertItem(int slot, @Nonnull ItemStack stack, boolean simulate) {
        if (!safe.automationPermits(LockAutomationPolicy.Operation.INSERT)) {
            return stack;
        }
        return super.insertItem(slot, stack, simulate);
    }

    @Nonnull
    @Override
    public ItemStack extractItem(int slot, int amount, boolean simulate) {
        if (!safe.automationPermits(LockAutomationPolicy.Operation.EXTRACT)) {
            return ItemStack.EMPTY;
        }
        return super.extractItem(slot, amount, simulate);
    }

    @Override
    public void setStackInSlot(int slot, @Nonnull ItemStack stack) {
        if (!safe.automationPermits(LockAutomationPolicy.Operation.INSERT)) {
            return;
        }
        super.setStackInSlot(slot, stack);
    }

    @Override
    public boolean isItemValid(int slot, @Nonnull ItemStack stack) {
        return safe.automationPermits(LockAutomationPolicy.Operation.INSERT)
                && super.isItemValid(slot, stack);
    }

    /** The slots the menu shows. The overflow a shrunken configuration left behind is not automatable. */
    @Override
    public int getSlots() {
        return safe.menuSlots();
    }
}
