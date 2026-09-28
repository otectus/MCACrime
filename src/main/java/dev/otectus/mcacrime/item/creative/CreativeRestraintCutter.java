package dev.otectus.mcacrime.item.creative;

import net.minecraft.world.item.Item;

/**
 * An operator tool that removes every restraint from whoever it is used on.
 *
 * <p>Physical only. Cutting somebody's cuffs off is not a pardon, does not end a sentence and does
 * not close a case — that boundary is the whole point of §1.4, and an operator tool that quietly
 * released prisoners from custody would be the easiest way to lose a jail's worth of legal state.
 *
 * <p>Use is gated by {@link CreativeAuthorization} on the server. The source gates it not at all.
 */
public class CreativeRestraintCutter extends Item {

    public CreativeRestraintCutter(Properties properties) {
        super(properties);
    }
}
