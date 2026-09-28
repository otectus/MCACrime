package dev.otectus.mcacrime.restraint;

/**
 * The action types a {@link RestrictionPolicy} decides, one constant per policy permission.
 *
 * <p>Action types rather than key codes, and that is the correction §7.1 asks for. Upstream blocks
 * raw key bindings on the client, which names other mods' keybinds by string, cannot see a modded
 * attack at all, and is unenforceable on a server. Everything here is something the server watches
 * happen.
 */
public enum RestraintAction {

    MINE_BLOCKS,
    USE_ITEM,
    ATTACK,
    INTERACT_ENTITY,
    INTERACT_BLOCK,
    DROP_ITEM,
    MUTATE_INVENTORY,
    SWAP_OFFHAND,
    CHANGE_HOTBAR,
    /** Movement the subject chooses. External escort, knockback and transport are not this. */
    VOLUNTARY_MOVEMENT,
    JUMP,
    SPRINT,
    STEER_VEHICLE,
    DISMOUNT
}
