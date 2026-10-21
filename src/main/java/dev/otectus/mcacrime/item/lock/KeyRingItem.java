package dev.otectus.mcacrime.item.lock;

import dev.otectus.mcacrime.help.CrimeHelpTopic;
import dev.otectus.mcacrime.help.TooltipHelp;
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

    /** How many key names a tooltip lists before it summarises the rest. */
    public static final int TOOLTIP_LABELS = 6;

    /**
     * The ring's keys and how to use it (0.8.0).
     *
     * <p>The capacity is the server's, from {@code ServerRulesS2CPacket}; until the server has said, the
     * line gives the count and says the limit is the server's, rather than quoting this client's own
     * config file as if it were the rule. Names are bounded to {@link #TOOLTIP_LABELS}: a key's anvil
     * name if it had one, the lock's name otherwise, and "an unnamed lock" when there is neither.
     */
    @Override
    public void appendHoverText(ItemStack stack, @Nullable Level level, List<Component> tooltip,
                                TooltipFlag flag) {
        super.appendHoverText(stack, level, tooltip, flag);
        List<KeyRingBindings.Entry> entries = KeyRingBindings.entries(stack.getTag());
        java.util.OptionalInt capacity = dev.otectus.mcacrime.network.ServerRulesView.maxKeysPerRing();
        tooltip.add((capacity.isPresent()
                ? Component.translatable("item.mcacrime.key_ring.tooltip.count", entries.size(), capacity.getAsInt())
                : Component.translatable("item.mcacrime.key_ring.tooltip.count_unknown", entries.size()))
                .withStyle(ChatFormatting.GRAY));
        for (int i = 0; i < entries.size() && i < TOOLTIP_LABELS; i++) {
            tooltip.add(Component.literal(" - ").append(label(entries.get(i))).withStyle(ChatFormatting.DARK_GRAY));
        }
        if (entries.size() > TOOLTIP_LABELS) {
            tooltip.add(Component.translatable("item.mcacrime.key_ring.tooltip.more", entries.size() - TOOLTIP_LABELS)
                    .withStyle(ChatFormatting.DARK_GRAY));
        }
        TooltipHelp.append(tooltip, Component.translatable(entries.isEmpty()
                        ? "item.mcacrime.key_ring.tooltip.empty" : "item.mcacrime.key_ring.tooltip.use"),
                List.of(Component.translatable("item.mcacrime.key_ring.tooltip.add"),
                        Component.translatable("item.mcacrime.key_ring.tooltip.remove"),
                        Component.translatable("item.mcacrime.key_ring.tooltip.first"),
                        Component.translatable("item.mcacrime.key_ring.tooltip.not_master")),
                CrimeHelpTopic.KEYRING);
    }

    /** One entry's readable name: the key's own name, then the lock's, then "an unnamed lock". */
    public static Component label(KeyRingBindings.Entry entry) {
        if (entry.customName() != null) {
            try {
                Component custom = Component.Serializer.fromJson(entry.customName());
                if (custom != null) {
                    return custom;
                }
            } catch (RuntimeException malformed) {
                // fall through to the lock's own name
            }
        }
        return KeyItem.label(entry.binding());
    }
}
