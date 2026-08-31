package dev.otectus.mcacrime.compat;

import dev.otectus.mcacrime.McaCrime;
import dev.otectus.mcacrime.compat.mca.McaHandles;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;

/**
 * The single point of contact with Minecraft Comes Alive: Reborn (spec §13). Every MCA symbol the mod
 * touches lives here, so MCA API drift is always a one-file fix and a wrong cast can never escape into
 * gameplay code (the bug that crashed legacy MCA, spec §0 rule 3).
 *
 * <p><b>No MCA type appears anywhere in this file.</b> Every MCA class and member is reached by name at
 * runtime through {@link McaHandles}, which probes MCA's package root rather than assuming one. That is
 * not gold-plating: MCA repackaged mid-version-line. Through 7.7.0-beta.2 its Forge classes live at
 * {@code forge.net.mca.*}; 7.7.1-alpha.1 renamed the base package and they became
 * {@code forge.net.conczin.mca.*}. This file previously opened with {@code instanceof VillagerEntityMCA}
 * and no {@code try}, so on the renamed build it threw
 * {@code NoClassDefFoundError: forge/net/mca/entity/VillagerEntityMCA} out of {@code CrimeGate} on
 * <em>every</em> {@code LivingHurtEvent} — a dedicated server died the instant anything took damage, and
 * no {@code catch} could have helped, because the JVM failed while resolving the type named in the
 * {@code instanceof} rather than while running the body.
 *
 * <p>"Karma/heat" are this mod's own concepts; MCA's per-player relationship value is "hearts", reached
 * through the villager's brain. Every method fails safe — on a non-MCA entity, absent MCA data, an
 * unbound member, or any throwable it returns a documented default and logs at DEBUG, never crashing the
 * server (spec §0 rule 4).
 */
public final class McaCompat {

    private McaCompat() {
    }

    /** True for an MCA human villager (adult or child; not the zombie variant). Never casts blindly. */
    public static boolean isMcaVillager(Entity entity) {
        return McaHandles.isVillager(entity);
    }

    public static UUID getVillagerUuid(Entity entity) {
        return entity.getUUID();
    }

    /** MCA overrides {@code getDisplayName} to return the villager's given name, so vanilla dispatch suffices. */
    public static Component getVillagerDisplayName(Entity entity) {
        return entity.getDisplayName();
    }

    /** Normalises the villager's profession to a {@link ResourceLocation} (spec §12). Safe default: empty. */
    public static Optional<ResourceLocation> getProfessionId(Entity entity) {
        try {
            return Optional.ofNullable(McaHandles.professionId(entity));
        } catch (Throwable t) {
            McaCrime.LOGGER.debug("MCA getProfessionId failed; defaulting empty", t);
            return Optional.empty();
        }
    }

    /**
     * True when the villager's profession is MCA's guard. Matches on the profession path so it is
     * namespace-agnostic (e.g. {@code mca:guard}). Safe default: {@code false}.
     */
    public static boolean isGuard(Entity entity) {
        return getProfessionId(entity).map(id -> "guard".equals(id.getPath())).orElse(false);
    }

    /**
     * Reads the player's current relationship hearts with this villager. Server-authoritative; returns
     * 0 for non-MCA entities or on any error. (Hearts are MCA's relationship currency, distinct from
     * this mod's Karma.)
     */
    public static int getHearts(ServerPlayer player, Entity villager) {
        return McaHandles.hearts(villager, player);
    }

    /**
     * Adds relationship hearts via MCA's own reward path (the one MCA's gifting uses). <b>Server side
     * only.</b> No-op for non-MCA entities or a zero amount. Used by later phases for relationship
     * consequences (spec §10.1) — wired now, exercised then.
     */
    public static void addHearts(ServerPlayer player, Entity villager, int amount) {
        if (amount == 0) {
            return;
        }
        McaHandles.rewardHearts(villager, player, amount);
    }

    /**
     * The id of the villager's MCA home village, or empty when it has none / on any error. The id is
     * MCA's own {@code int} village id, used as the key for per-village reputation (spec §2.5).
     */
    public static OptionalInt getHomeVillageId(Entity villager) {
        return McaHandles.homeVillageId(villager);
    }

    /**
     * The villager's current AI attack target, if any. Used to detect self-defense: if a villager is
     * already targeting the player, the player retaliating is lawful, not a crime (spec §5.2). An MCA
     * villager is a {@code Mob}, so {@code getTarget()} is valid; kept here so the cast never escapes.
     * Safe default: {@code empty}.
     */
    public static Optional<LivingEntity> getMcaTarget(Entity villager) {
        if (isMcaVillager(villager) && villager instanceof Mob mob) {
            try {
                return Optional.ofNullable(mob.getTarget());
            } catch (Throwable t) {
                McaCrime.LOGGER.debug("MCA getMcaTarget failed; defaulting empty", t);
            }
        }
        return Optional.empty();
    }

    /**
     * Makes an MCA guard pursue a player (spec §4.4). Best-effort: MCA guards use the Brain/behavior system
     * (a {@code NEAREST_GUARD_ENEMY} memory reclaimed by a sensor each tick) with no public make-hostile API,
     * so the reliable lever is vanilla {@code Mob.setTarget}, re-applied periodically by the enforcement
     * scan. Fail-safe: a no-op (returns false) for non-guards or on any error. <b>Server side only.</b>
     */
    public static boolean setGuardTarget(Entity guard, ServerPlayer target) {
        if (!isGuard(guard) || !(guard instanceof Mob mob)) {
            return false;
        }
        try {
            mob.setTarget(target);
            return true;
        } catch (Throwable t) {
            McaCrime.LOGGER.debug("MCA setGuardTarget failed; ignoring", t);
            return false;
        }
    }

    /** Clears a guard's target if it has one. Best-effort, server side only. */
    public static void clearGuardTarget(Entity guard) {
        if (guard instanceof Mob mob) {
            try {
                if (mob.getTarget() != null) {
                    mob.setTarget(null);
                }
            } catch (Throwable t) {
                McaCrime.LOGGER.debug("MCA clearGuardTarget failed; ignoring", t);
            }
        }
    }

    /** Clears only a stale target aimed at the specified player, preserving unrelated combat. */
    public static void clearGuardTarget(Entity guard, ServerPlayer formerTarget) {
        if (guard instanceof Mob mob) {
            try {
                if (mob.getTarget() == formerTarget) mob.setTarget(null);
            } catch (Throwable t) {
                McaCrime.LOGGER.debug("MCA conditional clearGuardTarget failed; ignoring", t);
            }
        }
    }

    /**
     * Best-effort "flee" for an MCA villager away from a (Red) player (spec §4.1): paths the villager away
     * using vanilla navigation, so it works without depending on MCA-internal AI. Fail-safe no-op on any
     * error. <b>Server side only.</b>
     */
    public static boolean makeVillagerFlee(Entity villager, ServerPlayer from) {
        if (!isMcaVillager(villager) || !(villager instanceof PathfinderMob mob)) {
            return false;
        }
        try {
            Vec3 away = mob.position().subtract(from.position());
            if (away.lengthSqr() < 1.0E-4) {
                away = new Vec3(1, 0, 0);
            }
            Vec3 dest = mob.position().add(away.normalize().scale(8.0));
            mob.getNavigation().moveTo(dest.x, dest.y, dest.z, 1.2);
            return true;
        } catch (Throwable t) {
            McaCrime.LOGGER.debug("MCA makeVillagerFlee failed; ignoring", t);
            return false;
        }
    }

    /**
     * Best-effort physical hold of a captured NPC by leashing it to its captor (spec §8.4). Uses the
     * vanilla {@code Mob} leash, persisted on the entity, so it survives chunk unload/reload without
     * depending on MCA-internal AI. The captive is never deleted — only leashed/moved. Fail-safe no-op on
     * any error. <b>Server side only.</b> ⚠ Leash interaction with MCA villager AI is an in-world
     * verification target (the dev runtime cannot load MCA).
     */
    public static boolean leashTo(Entity captive, Entity holder) {
        if (captive instanceof Mob mob) {
            try {
                mob.setLeashedTo(holder, true);
                return true;
            } catch (Throwable t) {
                McaCrime.LOGGER.debug("MCA leashTo failed; ignoring", t);
            }
        }
        return false;
    }

    /** Releases a leashed NPC captive on release (spec §8.4). Best-effort, never deletes the entity. */
    public static void clearLeash(Entity captive) {
        if (captive instanceof Mob mob) {
            try {
                if (mob.isLeashed()) {
                    mob.dropLeash(true, false);
                }
            } catch (Throwable t) {
                McaCrime.LOGGER.debug("MCA clearLeash failed; ignoring", t);
            }
        }
    }

    /**
     * Whether an NPC can fight back — so capture is never instant against it (spec §8.2). Conservative
     * default: a guard. (Broader MCA combat-archetype detection — Outlaws/Cultists — is a Phase 6 ⚠
     * verification target; until then guards are the combat NPCs that demand the full vulnerability+channel.)
     */
    public static boolean isCombatCapable(Entity entity) {
        return isGuard(entity);
    }

    /**
     * Whether an MCA villager is currently sleeping — a capture vulnerability (spec §8.2). Reads the vanilla
     * {@code LivingEntity} sleep state (MCA villagers sleep at night via the vanilla pose). Safe default:
     * {@code false}. (Player sleep is read directly off the player, not here.) ⚠ verified in-world.
     */
    public static boolean isVillagerSleeping(Entity entity) {
        if (isMcaVillager(entity) && entity instanceof LivingEntity living) {
            try {
                return living.isSleeping();
            } catch (Throwable t) {
                McaCrime.LOGGER.debug("MCA isVillagerSleeping failed; defaulting false", t);
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ relationship / family graph (§8.5)
    // Signatures verified byte-identical on 7.6.20+1.20.1, 7.7.0-beta.2+1.20.1 and 7.7.1-alpha.2+1.20.1;
    // McaBindingProbeTest re-checks all three on every build. Runtime behavior is an in-world ⚠
    // verification target. Every method fails safe so a differing MCA version degrades ransom to the
    // village-authority fallback rather than crashing.

    /**
     * Whether MCA's relationship API resolved. When false, {@code RansomService} skips the family payer
     * tiers and uses the village-authority fallback only (spec §8.5). Answered from the binding, so it
     * reflects the MCA that is actually installed rather than the one this mod was built against — the
     * old {@code Class.forName("forge.net.mca...")} probe could only ever confirm one package root, and
     * named classes this mod does not otherwise use.
     */
    public static boolean isRelationshipApiAvailable() {
        return McaHandles.relationshipApiAvailable();
    }

    /** The villager/player's spouse UUID (MCA partner), or empty. Safe default: empty. */
    public static Optional<UUID> getSpouseUuid(Entity entity) {
        return McaHandles.partnerUuid(entity);
    }

    public static List<UUID> getParentUuids(Entity entity) {
        return McaHandles.parentUuids(entity);
    }

    public static List<UUID> getChildUuids(Entity entity) {
        return McaHandles.childUuids(entity);
    }

    public static List<UUID> getSiblingUuids(Entity entity) {
        return McaHandles.siblingUuids(entity);
    }

    /** UUIDs of relatives up to {@code generations} away (grandparents/grandchildren/etc.). Safe default: empty. */
    public static List<UUID> getCloseRelativeUuids(Entity entity, int generations) {
        return McaHandles.closeRelativeUuids(entity, generations);
    }

    /**
     * Whether the entity is an adult (so adult-child ransom gating is correct, spec §8.5). Players/unknown
     * are treated as adults so a missing age never wrongly excludes a valid payer. Safe default: {@code true}
     * — which is also what a null age name means, covering both "not an MCA villager" and "unreadable".
     */
    public static boolean isAdult(Entity entity) {
        String age = McaHandles.ageStateName(entity);
        return age == null || "adult".equals(age);
    }
}
