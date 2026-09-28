package dev.otectus.mcacrime.item.lock;

import dev.otectus.mcacrime.item.CrimeItems;
import dev.otectus.mcacrime.locks.KeyBinding;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;

import javax.annotation.Nullable;
import java.util.List;
import java.util.Optional;

/**
 * A fired key mold: the reusable half of key copying, good for a bounded number of casts (ledger L04).
 *
 * <p>The quality is the remaining casts. It is a fixed number rather than a random one — upstream
 * rolls {@code r.nextInt(1) + 4}, which is a constant four dressed as a roll — so a player can be told
 * what they are getting and a recipe test can assert it.
 */
public class BakedKeyMoldItem extends Item {

    public static final String TAG_QUALITY = "Quality";
    /** How many keys one fired mold casts before it is spent. */
    public static final int STARTING_QUALITY = 4;

    public BakedKeyMoldItem(Properties properties) {
        super(properties);
    }

    /** Fires a clay mold, carrying the recorded binding across unchanged. */
    public static ItemStack fromRawMold(@Nullable ItemStack raw) {
        ItemStack baked = new ItemStack(CrimeItems.BAKED_KEY_MOLD.get());
        KeyMoldItem.recorded(raw).ifPresent(binding -> KeyMoldItem.store(baked, binding));
        baked.getOrCreateTag().putInt(TAG_QUALITY, STARTING_QUALITY);
        return baked;
    }

    /** One key cast from this mold, bound exactly as the original was. */
    public static ItemStack castKey(@Nullable ItemStack mold) {
        ItemStack key = new ItemStack(CrimeItems.KEY.get());
        KeyMoldItem.recorded(mold).ifPresent(binding -> binding.writeTo(key.getOrCreateTag()));
        return key;
    }

    /** The casts left in a mold. */
    public static int quality(@Nullable ItemStack mold) {
        if (mold == null || mold.isEmpty() || mold.getTag() == null) {
            return 0;
        }
        return Math.max(0, mold.getTag().getInt(TAG_QUALITY));
    }

    /**
     * The mold after one cast: the same mold with one fewer, or empty when it is spent.
     *
     * <p>Returning empty rather than a zero-quality mold is what makes "a spent mold is consumed"
     * a single rule the recipe's remaining-items list can simply hand back.
     */
    public static ItemStack spendOne(@Nullable ItemStack mold) {
        int remaining = quality(mold) - 1;
        if (mold == null || remaining <= 0) {
            return ItemStack.EMPTY;
        }
        ItemStack used = mold.copy();
        used.setCount(1);
        used.getOrCreateTag().putInt(TAG_QUALITY, remaining);
        return used;
    }

    /** Whether this mold still records a binding and has a cast left. */
    public static boolean usable(@Nullable ItemStack mold) {
        return quality(mold) > 0 && recorded(mold).isPresent();
    }

    private static Optional<KeyBinding> recorded(@Nullable ItemStack mold) {
        return KeyMoldItem.recorded(mold);
    }

    @Override
    public void appendHoverText(ItemStack stack, @Nullable Level level, List<Component> tooltip,
                                TooltipFlag flag) {
        super.appendHoverText(stack, level, tooltip, flag);
        tooltip.add(Component.translatable("item.mcacrime.baked_key_mold.tooltip.quality", quality(stack))
                .withStyle(ChatFormatting.DARK_GRAY));
    }
}
