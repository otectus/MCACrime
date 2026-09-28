package dev.otectus.mcacrime.frisk;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;

import java.util.List;

/**
 * One place a search can look (M5.2, spec §11.1).
 *
 * <p>An interface rather than a hard-coded walk of {@code Inventory}, because the specification asks
 * for vanilla, MCA equipment and optional Curios and Cosmetic Armor, and because "the search found
 * nothing because that mod was not installed" has to be an honest empty list rather than a crash.
 * A provider that cannot read its subject returns no slots and is skipped entirely.
 *
 * <p>Two rules every implementation keeps. {@link #peek} never mutates, so the projection the screen
 * shows can be rebuilt as often as it likes. {@link #extract} removes <em>exactly</em> the count it
 * is given or nothing at all, and returns what it removed — no partial silent takes, which is the
 * upstream frisking container's central defect ({@code FriskingContainer.removeItem} ignores its
 * {@code count} and moves the whole stack).
 *
 * <p>Villager trade offers are deliberately not a provider. They are not inventory stacks, and
 * confiscating one would delete a trade rather than take an item.
 */
public interface InventoryProvider {

    /** A stable id. Persisted in receipts, so it must not change between releases. */
    String id();

    /** Whether this provider has anything to say about this subject. */
    boolean supports(LivingEntity subject);

    /** The slots it offers, in a stable display order. Empty when it cannot read the subject. */
    List<FriskSlotRef> slots(LivingEntity subject);

    /** What is in a slot right now. Never mutates, never null; {@code ItemStack.EMPTY} for nothing. */
    ItemStack peek(LivingEntity subject, FriskSlotRef ref);

    /** Removes exactly {@code count} and returns it, or {@code ItemStack.EMPTY} and removes nothing. */
    ItemStack extract(LivingEntity subject, FriskSlotRef ref, int count);

    /**
     * Puts a stack back where it came from.
     *
     * <p>The rollback half of the transaction: if the destination refuses after the source was
     * debited, this is what makes the whole transfer a no-op rather than a loss.
     *
     * @return true when the slot took all of it
     */
    boolean restore(LivingEntity subject, FriskSlotRef ref, ItemStack stack);
}
