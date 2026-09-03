package dev.otectus.mcacrime.item.weapon;

import net.minecraft.resources.ResourceLocation;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The weapon classification rules, compiled from config and applied to a {@link WeaponProbe} (0.5.0).
 *
 * <p>Pure by construction: no registry, no tag manager, no {@code ItemStack}. That is what makes the
 * precedence testable, and precedence is the whole of the design. First match wins, in this order:
 *
 * <ol>
 *   <li>a restraint is never a weapon — cuffing somebody must not open the crime menu instead;</li>
 *   <li>the config blacklist, so an operator always has a final say;</li>
 *   <li>the config whitelist, so a server can arm a stick;</li>
 *   <li>the {@code mcacrime:weapons_blacklist} item tag, then the {@code mcacrime:weapons} tag, which
 *       is how a datapack or another mod contributes without touching anybody's config file;</li>
 *   <li>and only then automatic detection, which is what covers the mods nobody has listed.</li>
 * </ol>
 *
 * <p>Auto-detection ends with a bonus-attack-damage threshold, and that last rule is the one that
 * would otherwise arm a pickaxe: diggers other than axes are excluded immediately above it. Guns are
 * recognised by name and by namespace because no modded firearm extends any vanilla weapon class, and
 * the namespace rule additionally demands a non-stackable, non-block item so that a gun mod's ammo,
 * crafting components and gun-bench block do not all count as weapons too.
 *
 * <p>List entries are {@code namespace:path} for an item or {@code #namespace:path} for a tag.
 * Unparseable entries are skipped here and reported by {@code ConfigValidator}; one bad line must not
 * take the rest of the list with it.
 */
public final class WeaponRules {

    /** Items a datapack declares to be weapons. */
    public static final ResourceLocation WEAPONS_TAG = new ResourceLocation("mcacrime", "weapons");
    /** Items a datapack declares are never weapons; beaten only by the config blacklist above it. */
    public static final ResourceLocation WEAPONS_BLACKLIST_TAG = new ResourceLocation("mcacrime", "weapons_blacklist");

    private static final Set<String> RANGED_USE_ANIMS = Set.of("BOW", "CROSSBOW", "SPEAR");

    private final Set<ResourceLocation> whitelistIds;
    private final Set<ResourceLocation> whitelistTags;
    private final Set<ResourceLocation> blacklistIds;
    private final Set<ResourceLocation> blacklistTags;
    private final boolean autoDetect;
    private final double minAttackDamage;
    private final List<String> gunKeywords;
    private final Set<String> weaponMods;

    private WeaponRules(Set<ResourceLocation> whitelistIds, Set<ResourceLocation> whitelistTags,
                        Set<ResourceLocation> blacklistIds, Set<ResourceLocation> blacklistTags,
                        boolean autoDetect, double minAttackDamage,
                        List<String> gunKeywords, Set<String> weaponMods) {
        this.whitelistIds = whitelistIds;
        this.whitelistTags = whitelistTags;
        this.blacklistIds = blacklistIds;
        this.blacklistTags = blacklistTags;
        this.autoDetect = autoDetect;
        this.minAttackDamage = minAttackDamage;
        this.gunKeywords = gunKeywords;
        this.weaponMods = weaponMods;
    }

    /** Compiles the raw config values into a rule set. Malformed entries are dropped. */
    public static WeaponRules compile(List<? extends String> whitelist, List<? extends String> blacklist,
                                      boolean autoDetect, double minAttackDamage,
                                      List<? extends String> gunKeywords, List<? extends String> weaponMods) {
        Set<ResourceLocation> whiteIds = new LinkedHashSet<>();
        Set<ResourceLocation> whiteTags = new LinkedHashSet<>();
        parseInto(whitelist, whiteIds, whiteTags);
        Set<ResourceLocation> blackIds = new LinkedHashSet<>();
        Set<ResourceLocation> blackTags = new LinkedHashSet<>();
        parseInto(blacklist, blackIds, blackTags);
        return new WeaponRules(whiteIds, whiteTags, blackIds, blackTags, autoDetect, minAttackDamage,
                lowerCased(gunKeywords), Set.copyOf(lowerCased(weaponMods)));
    }

    /** What this item is, and which layer said so. */
    public WeaponMatch classify(WeaponProbe probe) {
        if (probe == null) {
            return WeaponMatch.none("empty");
        }
        if (probe.isRestraint()) {
            return WeaponMatch.none("restraint");
        }
        if (listed(probe, blacklistIds, blacklistTags)) {
            return WeaponMatch.none("config blacklist");
        }
        if (listed(probe, whitelistIds, whitelistTags)) {
            return new WeaponMatch(classOf(probe), "config whitelist");
        }
        if (probe.tagged(WEAPONS_BLACKLIST_TAG)) {
            return WeaponMatch.none("tag " + WEAPONS_BLACKLIST_TAG);
        }
        if (probe.tagged(WEAPONS_TAG)) {
            return new WeaponMatch(classOf(probe), "tag " + WEAPONS_TAG);
        }
        if (!autoDetect) {
            return WeaponMatch.none("autoDetect off");
        }
        if (probe.isSword() || probe.isAxe() || probe.isTrident()) {
            return new WeaponMatch(WeaponClass.MELEE, "auto: melee weapon type");
        }
        if (isRanged(probe)) {
            return new WeaponMatch(WeaponClass.RANGED, "auto: ranged weapon type");
        }
        if (hasGunKeyword(probe)) {
            return new WeaponMatch(WeaponClass.GUN, "auto: gun keyword");
        }
        if (isModdedGun(probe)) {
            return new WeaponMatch(WeaponClass.GUN, "auto: weapon mod namespace");
        }
        if (probe.isDiggerNonAxe()) {
            return WeaponMatch.none("auto: digging tool");
        }
        if (probe.attackDamage() >= minAttackDamage) {
            return new WeaponMatch(WeaponClass.MELEE, "auto: attack damage " + probe.attackDamage());
        }
        return WeaponMatch.none("no rule matched");
    }

    /** The threshold {@code /crime debug weapon} reports, so the printed number is the one in force. */
    public double minAttackDamage() {
        return minAttackDamage;
    }

    /**
     * The class an explicitly listed item gets: melee unless it shows a ranged or firearm signal.
     * Listing an item says it <em>is</em> a weapon; the signals only decide which kind.
     */
    private WeaponClass classOf(WeaponProbe probe) {
        if (hasGunKeyword(probe) || isModdedGun(probe)) {
            return WeaponClass.GUN;
        }
        if (isRanged(probe)) {
            return WeaponClass.RANGED;
        }
        return WeaponClass.MELEE;
    }

    private static boolean isRanged(WeaponProbe probe) {
        return probe.isProjectileWeapon()
                || (probe.useAnim() != null && RANGED_USE_ANIMS.contains(probe.useAnim().toUpperCase(Locale.ROOT)));
    }

    private boolean hasGunKeyword(WeaponProbe probe) {
        String path = probe.path().toLowerCase(Locale.ROOT);
        if (path.isEmpty()) {
            return false;
        }
        for (String keyword : gunKeywords) {
            if (!keyword.isEmpty() && path.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    private boolean isModdedGun(WeaponProbe probe) {
        return weaponMods.contains(probe.namespace().toLowerCase(Locale.ROOT))
                && !probe.isBlockItem() && !probe.isStackable();
    }

    private static boolean listed(WeaponProbe probe, Set<ResourceLocation> ids, Set<ResourceLocation> tags) {
        if (probe.id() != null && ids.contains(probe.id())) {
            return true;
        }
        for (ResourceLocation tag : tags) {
            if (probe.tagged(tag)) {
                return true;
            }
        }
        return false;
    }

    private static void parseInto(List<? extends String> raw, Set<ResourceLocation> ids, Set<ResourceLocation> tags) {
        if (raw == null) {
            return;
        }
        for (String entry : raw) {
            if (entry == null || entry.isBlank()) {
                continue;
            }
            String trimmed = entry.trim();
            boolean isTag = trimmed.startsWith("#");
            ResourceLocation id = ResourceLocation.tryParse(isTag ? trimmed.substring(1) : trimmed);
            if (id == null) {
                continue; // reported by ConfigValidator; dropping it keeps the rest of the list working
            }
            if (isTag) {
                tags.add(id);
            } else {
                ids.add(id);
            }
        }
    }

    private static List<String> lowerCased(List<? extends String> raw) {
        if (raw == null) {
            return List.of();
        }
        return raw.stream()
                .filter(s -> s != null && !s.isBlank())
                .map(s -> s.trim().toLowerCase(Locale.ROOT))
                .toList();
    }
}
