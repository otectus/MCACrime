package dev.otectus.mcacrime.item.lock;

import dev.otectus.mcacrime.locks.KeyBinding;
import dev.otectus.mcacrime.locks.KeyRingBindings;
import dev.otectus.mcacrime.locks.LockInteractions;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;

import javax.annotation.Nullable;
import java.util.List;

/**
 * A ring of bound keys (§3.7, ledger L03).
 *
 * <p>A bundle of keys, never a master key: it opens exactly the locks it carries a current key for.
 * Capacity is {@code locks.maxKeysPerRing}, and lowering that setting never erases anything — an
 * over-full ring keeps every key and simply refuses more.
 *
 * <p>Two upstream behaviours are deliberately absent. Its ring keeps a cosmetic {@code Keys} integer
 * stamped by the item's own tick to 1 or 2, which does not match the real number of bindings — and
 * then checks capacity against that number. And its index lookup compares UUIDs with {@code ==}
 * ({@code items/KeyRingItem.java:183}), so it essentially never finds a key it is holding. Here the
 * count is written from the list every time the list changes, and every comparison is
 * {@code equals}.
 */
public class KeyRingItem extends Item {

    public KeyRingItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        InteractionResult result = LockInteractions.useOnBlock(context);
        return result == InteractionResult.PASS ? super.useOn(context) : result;
    }

    /** Every binding on this ring. */
    public static List<KeyBinding> bindings(@Nullable ItemStack stack) {
        return stack == null || stack.isEmpty() ? List.of() : KeyRingBindings.read(stack.getTag());
    }

    /** Adds one binding if there is room. */
    public static boolean add(ItemStack stack, KeyBinding binding, int capacity) {
        return stack != null && !stack.isEmpty()
                && KeyRingBindings.add(stack.getOrCreateTag(), binding, capacity);
    }

    /** How many keys are on the ring, read from the list rather than from a stamped number. */
    public static int count(@Nullable ItemStack stack) {
        return stack == null || stack.isEmpty() ? 0 : KeyRingBindings.count(stack.getTag());
    }

    @Override
    public void appendHoverText(ItemStack stack, @Nullable Level level, List<Component> tooltip,
                                TooltipFlag flag) {
        super.appendHoverText(stack, level, tooltip, flag);
        int count = count(stack);
        tooltip.add(Component.translatable("item.mcacrime.key_ring.tooltip.count", count,
                LockInteractions.maxKeysPerRing()).withStyle(ChatFormatting.GRAY));
        for (KeyBinding binding : bindings(stack)) {
            binding.displayName().ifPresent(name ->
                    tooltip.add(Component.literal(" " + name).withStyle(ChatFormatting.DARK_GRAY)));
        }
    }
}
