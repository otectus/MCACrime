package dev.otectus.mcacrime;

import dev.otectus.mcacrime.item.weapon.WeaponClass;
import dev.otectus.mcacrime.item.weapon.WeaponMatch;
import dev.otectus.mcacrime.item.weapon.WeaponProbe;
import dev.otectus.mcacrime.item.weapon.WeaponRules;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The weapon classification rules, which are all precedence and therefore all worth testing.
 *
 * <p>These run with no Minecraft bootstrap: {@link WeaponProbe} is primitives and a tag predicate, so
 * every case here is the real rule set answering a real question about an item that does not exist.
 */
class WeaponRulesTest {

    private static final List<String> KEYWORDS = List.of("gun", "rifle", "pistol");
    private static final List<String> MODS = List.of("tacz");

    private static WeaponRules rules(List<String> whitelist, List<String> blacklist) {
        return WeaponRules.compile(whitelist, blacklist, true, 3.0, KEYWORDS, MODS);
    }

    /** A probe builder that keeps each test naming only the one or two facts it is about. */
    private static final class Probe {
        private String id = "minecraft:stick";
        private Set<String> tags = Set.of();
        private boolean restraint;
        private boolean sword;
        private boolean axe;
        private boolean trident;
        private boolean projectile;
        private boolean diggerNonAxe;
        private boolean block;
        private boolean stackable;
        private double damage;
        private String useAnim = "NONE";

        Probe id(String value) { this.id = value; return this; }
        Probe tags(String... values) { this.tags = Set.of(values); return this; }
        Probe restraint() { this.restraint = true; return this; }
        Probe sword() { this.sword = true; this.damage = 3.0; return this; }
        Probe digger() { this.diggerNonAxe = true; this.damage = 5.0; return this; }
        Probe projectile() { this.projectile = true; return this; }
        Probe block() { this.block = true; return this; }
        Probe stackable() { this.stackable = true; return this; }
        Probe damage(double value) { this.damage = value; return this; }
        Probe useAnim(String value) { this.useAnim = value; return this; }

        WeaponProbe build() {
            return new WeaponProbe(ResourceLocation.parse(id),
                    tag -> tags.contains(tag.toString()),
                    restraint, sword, axe, trident, projectile, diggerNonAxe, block, stackable, damage, useAnim);
        }
    }

    @Test
    void restraintIsNeverAWeaponEvenWhenWhitelisted() {
        WeaponMatch match = rules(List.of("mcacrime:restraint_rope"), List.of())
                .classify(new Probe().id("mcacrime:restraint_rope").restraint().build());
        assertFalse(match.isWeapon(), match.toString());
        assertEquals("restraint", match.layer());
    }

    @Test
    void blacklistBeatsWhitelist() {
        WeaponMatch match = rules(List.of("minecraft:iron_sword"), List.of("minecraft:iron_sword"))
                .classify(new Probe().id("minecraft:iron_sword").sword().build());
        assertFalse(match.isWeapon(), match.toString());
        assertEquals("config blacklist", match.layer());
    }

    @Test
    void whitelistArmsAnItemNothingElseWouldArm() {
        WeaponMatch match = rules(List.of("minecraft:stick"), List.of())
                .classify(new Probe().id("minecraft:stick").stackable().build());
        assertEquals(WeaponClass.MELEE, match.weaponClass());
        assertEquals("config whitelist", match.layer());
    }

    @Test
    void listEntriesStartingWithHashAreTagsAndPlainOnesAreItemIds() {
        WeaponRules tagged = rules(List.of("#forge:tools/spears"), List.of());
        assertTrue(tagged.classify(new Probe().id("modid:spear_of_nothing")
                .tags("forge:tools/spears").build()).isWeapon());
        // The same string without the hash is an item id, and this item's id is not that.
        WeaponRules byId = rules(List.of("forge:tools/spears"), List.of());
        assertFalse(byId.classify(new Probe().id("modid:spear_of_nothing")
                .tags("forge:tools/spears").build()).isWeapon());
    }

    @Test
    void datapackBlacklistTagLosesToTheConfigWhitelistAndBeatsTheWeaponsTag() {
        WeaponProbe excluded = new Probe().id("modid:ornamental_sword").sword()
                .tags(WeaponRules.WEAPONS_TAG.toString(), WeaponRules.WEAPONS_BLACKLIST_TAG.toString()).build();
        assertFalse(rules(List.of(), List.of()).classify(excluded).isWeapon());
        assertTrue(rules(List.of("modid:ornamental_sword"), List.of()).classify(excluded).isWeapon());
    }

    @Test
    void malformedEntriesAreSkippedAndTheRestOfTheListStillWorks() {
        WeaponRules compiled = rules(List.of("NOT AN ID", "minecraft:stick"), List.of());
        assertTrue(compiled.classify(new Probe().id("minecraft:stick").build()).isWeapon());
    }

    @Test
    void diggersAreExcludedBeforeTheDamageThreshold() {
        WeaponMatch match = rules(List.of(), List.of())
                .classify(new Probe().id("minecraft:diamond_pickaxe").digger().build());
        assertFalse(match.isWeapon(), match.toString());
        assertEquals("auto: digging tool", match.layer());
    }

    @Test
    void damageThresholdIsTheLastResortForUnlistedItems() {
        WeaponRules compiled = rules(List.of(), List.of());
        assertTrue(compiled.classify(new Probe().id("modid:club").damage(3.0).build()).isWeapon());
        assertFalse(compiled.classify(new Probe().id("modid:twig").damage(2.9).build()).isWeapon());
    }

    @Test
    void rangedIsRecognisedFromTheUseAnimationAlone() {
        WeaponMatch match = rules(List.of(), List.of())
                .classify(new Probe().id("modid:longbow").useAnim("BOW").build());
        assertEquals(WeaponClass.RANGED, match.weaponClass());
    }

    @Test
    void projectileWeaponsAreRangedWithoutAnyUseAnimation() {
        assertEquals(WeaponClass.RANGED, rules(List.of(), List.of())
                .classify(new Probe().id("modid:slingshot").projectile().build()).weaponClass());
    }

    @Test
    void gunKeywordInThePathClassifiesAsAGun() {
        assertEquals(WeaponClass.GUN, rules(List.of(), List.of())
                .classify(new Probe().id("someguns:hunting_rifle").build()).weaponClass());
    }

    @Test
    void weaponModNamespaceSkipsStackableAmmoAndBlocks() {
        WeaponRules compiled = rules(List.of(), List.of());
        assertEquals(WeaponClass.GUN,
                compiled.classify(new Probe().id("tacz:m4a1").build()).weaponClass());
        assertFalse(compiled.classify(new Probe().id("tacz:ammo_box").stackable().build()).isWeapon());
        assertFalse(compiled.classify(new Probe().id("tacz:workbench").block().build()).isWeapon(),
                "a block item is not a firearm just because a gun mod registered it");
    }

    @Test
    void autoDetectOffLeavesOnlyTheListsAndTheTag() {
        WeaponRules compiled = WeaponRules.compile(List.of("minecraft:stick"), List.of(), false, 3.0,
                KEYWORDS, MODS);
        assertFalse(compiled.classify(new Probe().id("minecraft:iron_sword").sword().build()).isWeapon());
        assertTrue(compiled.classify(new Probe().id("minecraft:stick").build()).isWeapon());
        assertTrue(compiled.classify(new Probe().id("modid:blade")
                .tags(WeaponRules.WEAPONS_TAG.toString()).build()).isWeapon());
    }
}
