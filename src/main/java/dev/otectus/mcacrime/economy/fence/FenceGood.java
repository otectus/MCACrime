package dev.otectus.mcacrime.economy.fence;

import net.minecraft.resources.ResourceLocation;

/**
 * One line of a fence's book: what the item is, what it is worth before any modifier, and which
 * directions it may travel in.
 *
 * <p>Keyed by {@link ResourceLocation} rather than by {@code Item} on purpose. The goods list is
 * assembled from tags, a datapack file and optional-mod providers, all of which speak in ids, and an
 * id for an item no installed mod registers has to survive being written down and then be skipped
 * quietly when the offers are built — which is exactly the "removing Locks later does not corrupt the
 * world data" acceptance in the spec.
 */
public record FenceGood(ResourceLocation item, long basePrice, boolean sells, boolean buys) {

    public FenceGood {
        basePrice = Math.max(1L, basePrice);
    }

    /** The union of two entries for the same item: the newest price, and either direction either allows. */
    public FenceGood mergedWith(FenceGood other) {
        return new FenceGood(item, other.basePrice(), sells || other.sells(), buys || other.buys());
    }

    public boolean tradeable() {
        return sells || buys;
    }
}
