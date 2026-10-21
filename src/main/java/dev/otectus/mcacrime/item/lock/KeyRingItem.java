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
 * ({@code items/KeyRingItem.java:183}), so it essentially never finds a key it is holding. Here there
 * is one list, {@link #count} is its length, and every comparison is {@code equals}. The client's
 * {@code mcacrime:keys} model predicate reads that same length, so the ring never draws a number of
 * keys it is not carrying.
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
    public static List<KeyBinding> bindings(ItemStack stack) {
        return KeyRingBindings.read(stack);
    }

    /** Adds one binding if there is room. */
    public static boolean add(ItemStack stack, KeyBinding binding, int capacity) {
        return KeyRingBindings.add(stack, binding, capacity);
    }

    /** How many keys are on the ring, read from the list rather than from a stamped number. */
    public static int count(ItemStack stack) {
        return KeyRingBindings.count(stack);
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip,
                                TooltipFlag flag) {
        super.appendHoverText(stack, context, tooltip, flag);
        // The capacity is the server's (ServerRulesS2CPacket, 0.8.0); until the server has said, the line
        // gives the count and says the limit is the server's. Names are bounded to TOOLTIP_LABELS.
        List<KeyBinding> keys = bindings(stack);
        java.util.Map<java.util.UUID, Component> labels = KeyRingBindings.labels(stack);
        java.util.OptionalInt capacity = dev.otectus.mcacrime.network.ServerRulesView.maxKeysPerRing();
        tooltip.add((capacity.isPresent()
                ? Component.translatable("item.mcacrime.key_ring.tooltip.count", keys.size(), capacity.getAsInt())
                : Component.translatable("item.mcacrime.key_ring.tooltip.count_unknown", keys.size()))
                .withStyle(ChatFormatting.GRAY));
        for (int i = 0; i < keys.size() && i < TOOLTIP_LABELS; i++) {
            Component name = labels.get(keys.get(i).lockId());
            tooltip.add(Component.literal(" - ").append(name != null ? name : KeyItem.label(keys.get(i)))
                    .withStyle(ChatFormatting.DARK_GRAY));
        }
        if (keys.size() > TOOLTIP_LABELS) {
            tooltip.add(Component.translatable("item.mcacrime.key_ring.tooltip.more", keys.size() - TOOLTIP_LABELS)
                    .withStyle(ChatFormatting.DARK_GRAY));
        }
        dev.otectus.mcacrime.help.TooltipHelp.append(tooltip, Component.translatable(keys.isEmpty()
                        ? "item.mcacrime.key_ring.tooltip.empty" : "item.mcacrime.key_ring.tooltip.use"),
                List.of(Component.translatable("item.mcacrime.key_ring.tooltip.add"),
                        Component.translatable("item.mcacrime.key_ring.tooltip.remove"),
                        Component.translatable("item.mcacrime.key_ring.tooltip.first"),
                        Component.translatable("item.mcacrime.key_ring.tooltip.not_master")),
                dev.otectus.mcacrime.help.CrimeHelpTopic.KEYRING);
    }

    /** How many key names a tooltip lists before it summarises the rest. */
    public static final int TOOLTIP_LABELS = 6;
}
