package dev.otectus.mcacrime.ai;

import dev.otectus.mcacrime.McaCrime;
import net.minecraft.core.registries.Registries;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.EntityType;

/**
 * The entity-type tags that let a datapack decide who resists a threat (0.5.1).
 *
 * <p>Tags rather than config lists because the question is per entity <em>type</em>, which is exactly
 * what a datapack is for, and because a modpack that adds a guard-like NPC should be able to make it
 * behave correctly without editing anybody's TOML. All three ship empty: the built-in answers
 * (guard, archer, weapon in hand) already cover vanilla MCA, and a tag with entries in it would be
 * this mod quietly making decisions about somebody else's mod's entities.
 */
public final class CrimeEntityTags {

    /** Never complies, whatever it is holding — guards, soldiers, anything a pack calls a combatant. */
    public static final TagKey<EntityType<?>> ALWAYS_RESISTS =
            TagKey.create(Registries.ENTITY_TYPE, McaCrime.id("always_resists_weapon_threats"));

    /** Always complies, whatever it is holding. Beats every other rule, including a drawn weapon. */
    public static final TagKey<EntityType<?>> NEVER_RESISTS =
            TagKey.create(Registries.ENTITY_TYPE, McaCrime.id("never_resists_weapon_threats"));

    /** Counts as armed by role even with empty hands; the last rule consulted before unarmed. */
    public static final TagKey<EntityType<?>> ARMED_VILLAGER_ROLES =
            TagKey.create(Registries.ENTITY_TYPE, McaCrime.id("armed_villager_roles"));

    /**
     * What sand cannot blind (0.7.2 §13.7). Unlike the three above this one ships <em>with</em>
     * entries: armour stands, iron golems, snow golems, wardens, withers and the ender dragon are
     * eyeless, mechanical or boss-immune for this mechanic by design. That is a game-design default
     * about a thrown bottle, not a claim about how any of those mobs actually perceive the world, and
     * a pack is free to replace the list entirely.
     *
     * <p>It is deliberately not the protected-victim list. {@code EntitySelectors.isProtected} treats
     * every MCA villager as a protected <em>victim</em>, and reusing it here would make ordinary
     * civilians immune to the one item whose whole purpose is disrupting them.
     */
    public static final TagKey<EntityType<?>> SAND_IMMUNE =
            TagKey.create(Registries.ENTITY_TYPE, McaCrime.id("sand_immune"));

    private CrimeEntityTags() {
    }
}
