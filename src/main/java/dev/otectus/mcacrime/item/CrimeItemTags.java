package dev.otectus.mcacrime.item;

import dev.otectus.mcacrime.McaCrime;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.ItemTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;

/**
 * Item tags for the criminal economy (0.5.1): what a thief may not take, and what a fence deals in.
 *
 * <p>Separate from {@link RestraintTags} because these are policy rather than mechanics. A pack
 * author changing what a thief can steal or what a fence buys should never have to think about which
 * blade cuts rope.
 *
 * <p>All five ship empty or near-empty on purpose. A theft-immunity list this mod invented would be
 * somebody else's opinion about which items matter; the tag exists so a pack can hold its own.
 */
public final class CrimeItemTags {

    /** Items a thief will never take, whatever slot they are in. Ships empty. */
    public static final TagKey<Item> THIEF_THEFT_IMMUNE =
            ItemTags.create(new ResourceLocation(McaCrime.MOD_ID, "thief_theft_immune"));

    /** The contraband a fence recognises at all (Phase 9). */
    public static final TagKey<Item> ILLICIT_GOODS =
            ItemTags.create(new ResourceLocation(McaCrime.MOD_ID, "illicit_goods"));

    /** What a fence will buy from a player (Phase 9). */
    public static final TagKey<Item> FENCE_BUYS =
            ItemTags.create(new ResourceLocation(McaCrime.MOD_ID, "fence_buys"));

    /** What a fence will sell to a player (Phase 9). */
    public static final TagKey<Item> FENCE_SELLS =
            ItemTags.create(new ResourceLocation(McaCrime.MOD_ID, "fence_sells"));

    /** Overrides the other three: anything here is untradeable however it was contributed (Phase 9). */
    public static final TagKey<Item> FENCE_BLACKLIST =
            ItemTags.create(new ResourceLocation(McaCrime.MOD_ID, "fence_blacklist"));

    private CrimeItemTags() {
    }
}
