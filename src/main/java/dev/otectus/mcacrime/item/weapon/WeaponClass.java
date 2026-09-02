package dev.otectus.mcacrime.item.weapon;

/**
 * What a held item counts as when the weapon trigger looks at it (0.5.0).
 *
 * <p>The distinction between {@link #MELEE}, {@link #RANGED} and {@link #GUN} is not used to gate
 * anything yet — every non-{@link #NONE} class is "armed". It is carried anyway because it is what
 * {@code /crime debug weapon} has to print to be useful, and because a rule that only ever answers
 * yes/no cannot later say "a drawn bow reads differently from a raised sword" without being rewritten.
 */
public enum WeaponClass {
    /** Not a weapon: a tool, a block, a restraint, or anything explicitly excluded. */
    NONE,
    /** Swung in the hand: swords, axes, tridents, anything with enough bonus attack damage. */
    MELEE,
    /** Drawn and released: bows, crossbows, thrown tridents. */
    RANGED,
    /** A modded firearm, recognised by name or by namespace rather than by any vanilla type. */
    GUN;

    /** Whether this class arms the player for the weapon trigger and for mugging. */
    public boolean isWeapon() {
        return this != NONE;
    }
}
