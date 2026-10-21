package dev.otectus.mcacrime.item.lock;

import dev.otectus.mcacrime.help.CrimeHelpTopic;
import dev.otectus.mcacrime.help.TooltipHelp;
import dev.otectus.mcacrime.locks.KeyBinding;
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
import java.util.Optional;

/**
 * A key bound to one lock identity (§3.7).
 *
 * <p>Distinct from {@code item/restraint/RestraintKeyItem}: that one opens a restraint family and
 * carries no identity, this one carries a {@code lockId} plus the binding revision that lock was on
 * when the key was cut. An old copy of a rekeyed lock's key must stop working, which a bare id
 * cannot express and which is why the revision travels with the binding rather than beside it.
 *
 * <p>The binding lives in item NBT on this branch ({@code DataComponentType} on the 1.21.1 port), and
 * every read and write of it goes through {@link KeyBinding} so the tag shape is stated once.
 *
 * <p>{@link #useOn} exists for the crouching click, which never reaches the block's own {@code use}.
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
    public static Optional<KeyBinding> binding(@Nullable ItemStack stack) {
        return stack == null || stack.isEmpty() ? Optional.empty() : KeyBinding.read(stack.getTag());
    }

    /** Writes a binding onto a stack. Used by the copying recipes and by the first bind. */
    public static void bind(ItemStack stack, KeyBinding binding) {
        if (stack != null && !stack.isEmpty() && binding != null) {
            binding.writeTo(stack.getOrCreateTag());
        }
    }

    /** Turns a key back into a blank. The reset recipe's whole effect. */
    public static void clear(ItemStack stack) {
        if (stack != null && !stack.isEmpty()) {
            KeyBinding.clear(stack.getTag());
        }
    }

    /**
     * What the key is and what to do with it (0.8.0): a blank key says how its first binding happens and
     * why a lock may refuse it; a bound key names its lock and how it is copied or wiped. Nothing here
     * claims the key still fits: that needs the lock's current revision, which only the server has.
     */
    @Override
    public void appendHoverText(ItemStack stack, @Nullable Level level, List<Component> tooltip,
                                TooltipFlag flag) {
        super.appendHoverText(stack, level, tooltip, flag);
        Optional<KeyBinding> binding = binding(stack);
        if (binding.isEmpty()) {
            TooltipHelp.append(tooltip, Component.translatable("item.mcacrime.key.tooltip.blank"),
                    List.of(Component.translatable("item.mcacrime.key.tooltip.blank.1"),
                            Component.translatable("item.mcacrime.key.tooltip.blank.2")),
                    CrimeHelpTopic.KEYS);
            return;
        }
        TooltipHelp.append(tooltip, Component.translatable("item.mcacrime.key.tooltip.bound", label(binding.get())),
                List.of(Component.translatable("item.mcacrime.key.tooltip.bound.1"),
                        Component.translatable("item.mcacrime.key.tooltip.bound.2"),
                        Component.translatable("item.mcacrime.key.tooltip.bound.3")),
                CrimeHelpTopic.KEYMOLD);
    }

    /** A binding's readable name, or "an unnamed lock". Never an id or a lock code. */
    public static Component label(KeyBinding binding) {
        return binding.displayName().<Component>map(Component::literal)
                .orElseGet(() -> Component.translatable("item.mcacrime.key.tooltip.unnamed"));
    }
}
