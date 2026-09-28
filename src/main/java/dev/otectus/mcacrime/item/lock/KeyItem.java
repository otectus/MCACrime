package dev.otectus.mcacrime.item.lock;

import dev.otectus.mcacrime.locks.KeyBinding;
import dev.otectus.mcacrime.locks.LockInteractions;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.UseOnContext;

import java.util.List;
import java.util.Optional;

/**
 * A key bound to one lock identity (§3.7).
 *
 * <p>The binding is identity <em>plus</em> revision, never a bare id: that pair is what lets a lock be
 * rekeyed without being replaced, so every copy of the old key stops working while the door, the
 * padlock and every audit reference keep naming the same lock. Both halves are stamped on together
 * when the key was cut. An old copy of a rekeyed lock's key must stop working, which a bare id
 * cannot express and which is why the revision travels with the binding rather than beside it.
 *
 * <p>The binding lives in a registered {@code DataComponentType} on this branch (item NBT on the
 * Forge 1.20.1 baseline), and every read and write of it goes through {@link KeyBinding} so the shape
 * is stated once.
 *
 * <p>{@link #useOn} exists for the crouching click, which never reaches the block's own interaction.
 * The work itself is {@code LockInteractions}': one routing table serves the key, the ring, the door,
 * the safe and the padlock, rather than each of them carrying its own copy of the same five branches.
 */
public class KeyItem extends Item {

    public KeyItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        InteractionResult result = LockInteractions.useOnBlock(context);
        return result == InteractionResult.PASS ? super.useOn(context) : result;
    }

    /** The binding on a key stack, if it has been cut for something. */
    public static Optional<KeyBinding> binding(ItemStack stack) {
        return KeyBinding.read(stack);
    }

    /** Writes a binding onto a stack. Used by the copying recipes and by the first bind. */
    public static void bind(ItemStack stack, KeyBinding binding) {
        if (binding != null) {
            binding.writeTo(stack);
        }
    }

    /** Turns a key back into a blank. The reset recipe's whole effect. */
    public static void clear(ItemStack stack) {
        KeyBinding.clear(stack);
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip,
                                TooltipFlag flag) {
        super.appendHoverText(stack, context, tooltip, flag);
        Optional<KeyBinding> binding = binding(stack);
        if (binding.isEmpty()) {
            tooltip.add(Component.translatable("item.mcacrime.key.tooltip.blank")
                    .withStyle(ChatFormatting.DARK_GRAY));
            return;
        }
        tooltip.add(Component.translatable("item.mcacrime.key.tooltip.bound",
                        binding.get().displayName()
                                .map(Component::literal)
                                .orElseGet(() -> Component.translatable("item.mcacrime.key.tooltip.unnamed")))
                .withStyle(ChatFormatting.GRAY));
    }
}
