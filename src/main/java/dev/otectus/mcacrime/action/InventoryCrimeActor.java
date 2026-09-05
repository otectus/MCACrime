package dev.otectus.mcacrime.action;

import net.minecraft.world.entity.player.Inventory;

/**
 * An actor with a player inventory (0.5.1).
 *
 * <p>Deliberately a capability of its own and deliberately player-only: an MCA villager has no
 * {@link Inventory}, and pretending otherwise is how a handler ends up calling {@code asPlayer()} and
 * throwing on the first NPC that reaches it. Item theft asks for this interface, so the compiler is
 * what stops it from being pointed at a villager rather than a null check somebody has to remember.
 */
public interface InventoryCrimeActor extends CrimeActor {

    /** The actor's inventory. Never null for an implementation of this interface. */
    Inventory inventory();
}
