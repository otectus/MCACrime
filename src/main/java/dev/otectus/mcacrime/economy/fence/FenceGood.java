package dev.otectus.mcacrime.economy.fence;

import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

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

    /**
     * This entry with the stated directions replaced, leaving an unstated one alone.
     *
     * <p>The union above is the right rule for a contribution that only ever adds stock — a tag, a
     * provider — but it leaves a pack no way to take a direction back off an item a tag introduced.
     * A price file that says {@code "buys": false} means it, so that flag replaces rather than merges
     * (0.6.0); a file that says nothing about a direction still merges.
     *
     * @param sells the stated value, or null when the source said nothing about this direction
     * @param buys  the same, for the other direction
     */
    public FenceGood withDirections(@Nullable Boolean sells, @Nullable Boolean buys) {
        return new FenceGood(item, basePrice,
                sells == null ? this.sells : sells,
                buys == null ? this.buys : buys);
    }

    public boolean tradeable() {
        return sells || buys;
    }
}
