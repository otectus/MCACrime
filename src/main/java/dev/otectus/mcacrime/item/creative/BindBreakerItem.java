package dev.otectus.mcacrime.item.creative;

import net.minecraft.world.item.Item;

/**
 * An operator tool that clears a subject's physical state outright: gear, tether and device.
 *
 * <p>The recovery tool for a stuck subject, and the reason it is separate from the cutter: the
 * cutter removes worn restraints, this also drops a tether and empties a device occupancy, which is
 * what an operator needs when something has gone wrong rather than when a prisoner should go free.
 * It is still not a release, and it is still gated by {@link CreativeAuthorization}.
 */
public class BindBreakerItem extends Item {

    public BindBreakerItem(Properties properties) {
        super(properties);
    }
}
