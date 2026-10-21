package dev.otectus.mcacrime.item.lock;

import dev.otectus.mcacrime.item.CrimeItems;
import dev.otectus.mcacrime.locks.KeyBinding;
import dev.otectus.mcacrime.state.CrimeDataComponents;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;

import org.jetbrains.annotations.Nullable;
import java.util.List;
import java.util.Optional;

/**
 * A clay impression of a key, taken before it is baked (§3.7, ledger L04).
 *
 * <p>Carries the whole binding — identity <em>and</em> revision — so a copy made from a mold of a key
 * that was cut before a rekey is as useless as the original, rather than mysteriously working.
 *
 * <p>The copied binding lives in its own {@code mcacrime:key_mold_binding} component rather than in
 * the key's, which keeps "this is a record of a key" distinct from "this is a key": a mold is not
 * usable in a lock and must never be mistaken for one by a component read.
 */
public class KeyMoldItem extends Item {

    public KeyMoldItem(Properties properties) {
        super(properties);
    }

    /** A mold taken from a bound key. A blank key makes a blank mold. */
    public static ItemStack fromKey(@Nullable ItemStack key) {
        ItemStack mold = new ItemStack(CrimeItems.KEY_MOLD.get());
        KeyBinding.read(key).ifPresent(binding -> store(mold, binding));
        return mold;
    }

    /** Writes a recorded binding into a mold-shaped stack. Shared with the baked mold. */
    public static void store(ItemStack mold, KeyBinding binding) {
        if (mold == null || mold.isEmpty() || binding == null) {
            return;
        }
        mold.set(CrimeDataComponents.KEY_MOLD_BINDING.get(), binding);
    }

    /** The binding a mold records, if it records one. */
    public static Optional<KeyBinding> recorded(@Nullable ItemStack mold) {
        if (mold == null || mold.isEmpty()) {
            return Optional.empty();
        }
        return Optional.ofNullable(mold.get(CrimeDataComponents.KEY_MOLD_BINDING.get()));
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip,
                                TooltipFlag flag) {
        super.appendHoverText(stack, context, tooltip, flag);
        // Whether the mold holds an impression, whose, and the next step (0.8.0).
        java.util.Optional<KeyBinding> binding = recorded(stack);
        if (binding.isPresent()) {
            dev.otectus.mcacrime.help.TooltipHelp.append(tooltip, Component.translatable(
                            "item.mcacrime.key_mold.tooltip.bound", KeyItem.label(binding.get())),
                    List.of(Component.translatable("item.mcacrime.key_mold.tooltip.bound.1"),
                            Component.translatable("item.mcacrime.key_mold.tooltip.bound.2")),
                    dev.otectus.mcacrime.help.CrimeHelpTopic.KEYMOLD);
            return;
        }
        dev.otectus.mcacrime.help.TooltipHelp.append(tooltip, Component.translatable("item.mcacrime.key_mold.tooltip.blank"),
                List.of(Component.translatable("item.mcacrime.key_mold.tooltip.blank.1"),
                        Component.translatable("item.mcacrime.key_mold.tooltip.blank.2")),
                dev.otectus.mcacrime.help.CrimeHelpTopic.KEYMOLD);
    }
}
