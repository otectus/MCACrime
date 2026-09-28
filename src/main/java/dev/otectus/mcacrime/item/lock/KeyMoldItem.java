package dev.otectus.mcacrime.item.lock;

import dev.otectus.mcacrime.item.CrimeItems;
import dev.otectus.mcacrime.locks.KeyBinding;
import net.minecraft.ChatFormatting;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;

import javax.annotation.Nullable;
import java.util.List;
import java.util.Optional;

/**
 * A clay impression of a key, taken before it is baked (§3.7, ledger L04).
 *
 * <p>Carries the whole binding — identity <em>and</em> revision — so a copy made from a mold of a key
 * that was cut before a rekey is as useless as the original, rather than mysteriously working.
 *
 * <p>The copied binding lives in a nested {@code CopiedKey} compound rather than on the root tag,
 * which keeps "this is a record of a key" distinct from "this is a key": a mold is not usable in a
 * lock and must never be mistaken for one by a tag read.
 */
public class KeyMoldItem extends Item {

    public KeyMoldItem(Properties properties) {
        super(properties);
    }

    /** A mold taken from a bound key. A blank key makes a blank mold. */
    public static ItemStack fromKey(@Nullable ItemStack key) {
        ItemStack mold = new ItemStack(CrimeItems.KEY_MOLD.get());
        copied(key).ifPresent(binding -> store(mold, binding));
        return mold;
    }

    /** The binding a key stack carries, for the mold to record. */
    private static Optional<KeyBinding> copied(@Nullable ItemStack key) {
        return key == null || key.isEmpty() ? Optional.empty() : KeyBinding.read(key.getTag());
    }

    /** Writes a recorded binding into a mold-shaped stack. Shared with the baked mold. */
    public static void store(ItemStack mold, KeyBinding binding) {
        if (mold == null || mold.isEmpty() || binding == null) {
            return;
        }
        mold.getOrCreateTag().put(KeyBinding.TAG_COPIED_KEY, binding.save());
    }

    /** The binding a mold records, if it records one. */
    public static Optional<KeyBinding> recorded(@Nullable ItemStack mold) {
        if (mold == null || mold.isEmpty()) {
            return Optional.empty();
        }
        CompoundTag tag = mold.getTag();
        if (tag == null || !tag.contains(KeyBinding.TAG_COPIED_KEY)) {
            return Optional.empty();
        }
        return KeyBinding.load(tag.getCompound(KeyBinding.TAG_COPIED_KEY));
    }

    @Override
    public void appendHoverText(ItemStack stack, @Nullable Level level, List<Component> tooltip,
                                TooltipFlag flag) {
        super.appendHoverText(stack, level, tooltip, flag);
        tooltip.add(Component.translatable(recorded(stack).isPresent()
                        ? "item.mcacrime.key_mold.tooltip.bound"
                        : "item.mcacrime.key_mold.tooltip.blank")
                .withStyle(ChatFormatting.GRAY));
    }
}
