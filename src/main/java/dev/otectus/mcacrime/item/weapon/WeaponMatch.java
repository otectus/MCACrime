package dev.otectus.mcacrime.item.weapon;

/**
 * One classification result: what the item counts as, and which rule layer decided it.
 *
 * <p>The layer name is the reason {@code /crime debug weapon} exists. "This sword is not a weapon" is
 * unactionable; "blacklisted by config" and "excluded as a digging tool" name the exact line an
 * operator has to change.
 */
public record WeaponMatch(WeaponClass weaponClass, String layer) {

    public static WeaponMatch none(String layer) {
        return new WeaponMatch(WeaponClass.NONE, layer);
    }

    public boolean isWeapon() {
        return weaponClass.isWeapon();
    }
}
