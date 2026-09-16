package dev.otectus.mcacrime.item;

import dev.otectus.mcacrime.McaCrime;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.ItemTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.neoforged.neoforge.common.Tags;

/**
 * Item tags for restraints (spec §8.3). {@code c:ropes} is the broad-compatibility tag so any modded
 * rope counts as a rope-strength restraint; {@code mcacrime:restraints} groups this mod's three items.
 */
public final class RestraintTags {

    /**
     * Broad cross-mod rope tag — any item here works as a ROPE restraint. The 1.21.1 common-tag
     * convention replaces {@code forge:rope}; the shipped {@code data/c/tags/item/ropes.json} keeps
     * this mod's rope and the vanilla lead in it.
     */
    public static final TagKey<Item> ROPE = Tags.Items.ROPES;
    /** This mod's restraints. */
    public static final TagKey<Item> RESTRAINTS = ItemTags.create(ResourceLocation.fromNamespaceAndPath(McaCrime.MOD_ID, "restraints"));

    /**
     * What counts as a mask (0.7.0). A tag rather than a class check so a pack can make a hood, a
     * carved pumpkin or a modded helmet hide a face without a code change; the sixteen shipped styles
     * are simply the sixteen entries this mod puts in it.
     */
    public static final TagKey<Item> MASKS = ItemTags.create(ResourceLocation.fromNamespaceAndPath(McaCrime.MOD_ID, "masks"));

    /**
     * The four material families, as sub-tags of {@link #MASKS} (0.7.2 section 7.4).
     *
     * <p>These exist so one restyle recipe can say "any clay mask" in a single ingredient. Membership
     * here is <em>not</em> permission to rewrite an item's data: the protected restyle path additionally
     * requires {@code MaskVariant.byItem} to answer with a registered style, so a pack that adds a
     * third-party helmet to {@code masks/clay} has made it conceal a face, not made it convertible.
     * Reach these through {@code MaskFamily#tag()} rather than by name.
     */
    public static final TagKey<Item> MASKS_CLOTH = maskFamily("cloth");
    public static final TagKey<Item> MASKS_LEATHER = maskFamily("leather");
    public static final TagKey<Item> MASKS_CLAY = maskFamily("clay");
    public static final TagKey<Item> MASKS_METAL = maskFamily("metal");

    private static TagKey<Item> maskFamily(String family) {
        return ItemTags.create(ResourceLocation.fromNamespaceAndPath(McaCrime.MOD_ID, "masks/" + family));
    }

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
