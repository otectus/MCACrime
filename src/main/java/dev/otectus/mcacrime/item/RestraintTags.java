package dev.otectus.mcacrime.item;

import dev.otectus.mcacrime.McaCrime;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.ItemTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;

/**
 * Item tags for restraints (spec §8.3). {@code forge:rope} is the broad-compatibility tag so any modded
 * rope counts as a rope-strength restraint; {@code mcacrime:restraints} groups this mod's three items.
 */
public final class RestraintTags {

    /** Broad cross-mod rope tag — any item here works as a ROPE restraint. */
    public static final TagKey<Item> ROPE = ItemTags.create(ResourceLocation.fromNamespaceAndPath("forge", "rope"));
    /** This mod's restraints. */
    public static final TagKey<Item> RESTRAINTS = ItemTags.create(ResourceLocation.fromNamespaceAndPath(McaCrime.MOD_ID, "restraints"));

    /**
     * What can cut somebody free. Rope yields to any blade; cuffs do not, which is the whole reason
     * the two restraints are worth different amounts of the rescuer's time.
     */
    public static final TagKey<Item> CUTTING_TOOLS =
            ItemTags.create(ResourceLocation.fromNamespaceAndPath(McaCrime.MOD_ID, "cutting_tools"));

    /**
     * What opens a lock. Deliberately near-empty by default: the shipped tag holds only the tripwire
     * hook, because vanilla has no key item and inventing one would be a bigger design decision than
     * this release is making. A server with a lock mod installed adds its keys to this tag and locked
     * cuffs immediately become openable by them, with no code change here.
     */
    public static final TagKey<Item> KEYS = ItemTags.create(ResourceLocation.fromNamespaceAndPath(McaCrime.MOD_ID, "keys"));

    private RestraintTags() {
    }
}
