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
    public static final TagKey<Item> ROPE = ItemTags.create(new ResourceLocation("forge", "rope"));
    /** This mod's restraints. */
    public static final TagKey<Item> RESTRAINTS = ItemTags.create(new ResourceLocation(McaCrime.MOD_ID, "restraints"));

    private RestraintTags() {
    }
}
