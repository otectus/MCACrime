package dev.otectus.mcacrime.item.contraband;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.ItemStack;

import java.util.function.Predicate;

/**
 * Everything {@link ContrabandRules} is allowed to know about one stack in a player's inventory (0.7.0).
 *
 * <p>Built the same way {@code WeaponProbe} is, and for the same reason: the rules are the part with
 * parsing, precedence and edge cases worth testing, and they are testable only if they never touch a
 * registry, a tag manager, or an {@code ItemStack}. {@code ContrabandScanAdapter} does the touching.
 *
 * @param itemId      the stack's registry id, or {@code null} if it is somehow unregistered
 * @param tagged      whether the stack carries a given item tag
 * @param count       how many items the stack holds, which is what the fingerprint weighs
 * @param depth       0 for a stack in the inventory proper, 1 for one nested in a container
 * @param displayName the stack's display name, for the message a guard's find produces
 * @param slot        where the stack was found, so the equipped/offhand toggles can be honoured
 */
public record ContrabandProbe(ResourceLocation itemId,
                              Predicate<ResourceLocation> tagged,
                              int count,
                              int depth,
                              String displayName,
                              ContrabandSlot slot) {

    /** Which part of the inventory a probe came from. */
    public enum ContrabandSlot {
        MAIN,
        ARMOR,
        OFFHAND,
        NESTED
    }

    /**
     * Reduces a real stack to a probe. The tag lookup arrives as a predicate for the same reason the
     * weapon probe does it: the rules stay pure and the registry call stays here.
     */
    public static ContrabandProbe of(ItemStack stack, ContrabandSlot slot, int depth) {
        return new ContrabandProbe(
                BuiltInRegistries.ITEM.getKey(stack.getItem()),
                tag -> stack.is(TagKey.create(Registries.ITEM, tag)),
                stack.getCount(),
                depth,
                stack.getHoverName().getString(),
                slot);
    }

    /** Whether the stack carries the given tag id; a probe with no tag source answers no. */
    public boolean hasTag(ResourceLocation tag) {
        return tagged != null && tag != null && tagged.test(tag);
    }

    /** The id as a string, or {@code ""} when unregistered — the fingerprint's sort key. */
    public String idString() {
        return itemId == null ? "" : itemId.toString();
    }
}
