package dev.otectus.mcacrime.frisk;

/**
 * Which part of a subject a searchable slot belongs to (M5.2).
 *
 * <p>Presentation and policy both read this: the screen groups by it, and a server that wants a
 * pat-down to reach clothing but not a backpack has one thing to name. Deliberately coarse — a
 * provider decides which of its slots map to which kind, so a mod's belt and a villager's satchel
 * can both be {@link #EQUIPMENT} without this enum growing per mod.
 */
public enum FriskSlotKind {

    /** The nine hotbar slots. */
    HOTBAR,
    /** The main inventory grid. */
    MAIN,
    /** Worn armour. */
    ARMOUR,
    /** The off hand. */
    OFFHAND,
    /** Anything a provider surfaces that is none of the above — MCA clothing, a mod's curio. */
    EQUIPMENT
}
