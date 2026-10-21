package dev.otectus.mcacrime.mask;

import net.minecraft.core.cauldron.CauldronInteraction;
import net.minecraft.world.item.Item;

/**
 * Washing a dyed mask in a water cauldron (0.8.0).
 *
 * <p>Being in the {@code minecraft:dyeable} tag does not register a cauldron interaction: vanilla lists the
 * leather pieces and wolf armour in {@link CauldronInteraction#WATER} by hand, so a custom dyeable item is
 * never washed unless it is added there too. Each mask gets vanilla's own {@link CauldronInteraction#DYED_ITEM}:
 * it removes only the {@code minecraft:dyed_color} component (a name, lore and every other component stay),
 * lowers the water by one level once, awards the vanilla "armour cleaned" statistic, and does nothing at
 * all to an undyed mask. Colour is cosmetic, so washing touches no Heat, witness or legal state either --
 * none of which lives on the item.
 */
public final class MaskWashing {

    private MaskWashing() {
    }

    /** Called once from common setup, inside {@code enqueueWork}: the map is not thread-safe. */
    public static void register(Iterable<Item> masks) {
        for (Item mask : masks) {
            CauldronInteraction.WATER.map().put(mask, CauldronInteraction.DYED_ITEM);
        }
    }
}
