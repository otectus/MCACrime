package dev.otectus.mcacrime.item.creative;

import net.minecraft.world.item.Item;

/**
 * An operator key that opens any lock and any restraint family.
 *
 * <p>Gated by {@link CreativeAuthorization}, for the same reason the cutter is: a master key that
 * works for whoever picked it up is a master key for the server's safes.
 */
public class CreativeKeyItem extends Item {

    public CreativeKeyItem(Properties properties) {
        super(properties);
    }
}
