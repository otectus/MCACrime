package dev.otectus.mcacrime.compat;

import dev.otectus.mcacrime.McaCrime;
import forge.net.mca.entity.VillagerEntityMCA;
import forge.net.mca.entity.VillagerLike;
import forge.net.mca.entity.ai.Memories;
import forge.net.mca.entity.ai.relationship.AgeState;
import forge.net.mca.entity.ai.relationship.EntityRelationship;
import forge.net.mca.server.world.data.FamilyTreeNode;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * The single point of contact with Minecraft Comes Alive: Reborn (spec §13). Every MCA symbol the mod
 * touches lives here, so MCA API drift is always a one-file fix and a wrong cast can never escape into
 * gameplay code (the bug that crashed legacy MCA, spec §0 rule 3).
 *
 * <p><b>Why {@code forge.net.mca.*} and not {@code net.mca.*}?</b> MCA Reborn ships a Forgix-merged
 * "Universal" jar; Forgix relocates the Forge classes under a {@code forge.} root package, so they are
 * physically {@code forge.net.mca.*} in both the production jar and our dev-remapped (deobf) jar. There
 * is no runtime restoration to {@code net.mca.*}. (MCA's own source is {@code net.mca.*}; the prefix is
 * added at merge time.)
 *
 * <p>"Karma/heat" are this mod's own concepts; MCA's per-player relationship value is "hearts"
 * ({@link Memories#getHearts()}), reached via {@code villager.getVillagerBrain().getMemoriesForPlayer(player)}.
 * Phase 1 only reads/writes hearts for relationship consequences in later phases; the methods are wired
 * and reachable now via {@code /crime debug villager}. Every method fails safe — on a non-MCA entity,
 * null MCA data, or any throwable it returns a documented default and logs at DEBUG, never crashing the
 * server (spec §0 rule 4).
 */
public final class McaCompat {

    /** Cached result of the one-time relationship-API availability probe (spec §8.5 graceful degradation). */
    private static volatile Boolean relationshipApiOk;

    private McaCompat() {
    }

    /** True for an MCA human villager (adult or child; not the zombie variant). Never casts blindly. */
    public static boolean isMcaVillager(Entity entity) {
        return entity instanceof VillagerEntityMCA;
    }

    public static UUID getVillagerUuid(Entity entity) {
        return entity.getUUID();
    }

    public static Component getVillagerDisplayName(Entity entity) {
        return entity instanceof VillagerEntityMCA villager ? villager.getDisplayName() : entity.getDisplayName();
    }

    /** Normalises the villager's profession to a {@link ResourceLocation} (spec §12). Safe default: empty. */
    public static Optional<ResourceLocation> getProfessionId(Entity entity) {
        if (entity instanceof VillagerLike<?> villager) {
            try {
                return Optional.ofNullable(villager.getProfessionId());
            } catch (Throwable t) {
                McaCrime.LOGGER.debug("MCA getProfessionId failed; defaulting empty", t);
            }
        }
        return Optional.empty();
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
        if (villager instanceof VillagerEntityMCA mca) {
            try {
                Memories memories = mca.getVillagerBrain().getMemoriesForPlayer(player);
                return memories == null ? 0 : memories.getHearts();
            } catch (Throwable t) {
                McaCrime.LOGGER.debug("MCA getHearts failed; defaulting 0", t);
            }
        }
        return 0;
    }

    /**
     * Adds relationship hearts via MCA's own {@code VillagerBrain.rewardHearts} (the path MCA's gifting
     * uses). <b>Server side only.</b> No-op for non-MCA entities or a zero amount. Used by later phases
     * for relationship consequences (spec §10.1) — wired now, exercised then.
     */
    public static void addHearts(ServerPlayer player, Entity villager, int amount) {
        if (amount == 0) {
            return;
        }
        if (villager instanceof VillagerEntityMCA mca) {
            try {
                mca.getVillagerBrain().rewardHearts(player, amount);
            } catch (Throwable t) {
                McaCrime.LOGGER.debug("MCA addHearts failed; ignoring", t);
            }
        }
    }

    /**
     * The id of the villager's MCA home village, or empty when it has none / on any error. The id is
     * MCA's own {@code int} village id, used as the key for per-village reputation (spec §2.5).
     */
    public static OptionalInt getHomeVillageId(Entity villager) {
        if (villager instanceof VillagerEntityMCA mca) {
            try {
                return mca.getResidency().getHomeVillage()
                        .map(v -> OptionalInt.of(v.getId()))
                        .orElseGet(OptionalInt::empty);
            } catch (Throwable t) {
                McaCrime.LOGGER.debug("MCA getHomeVillageId failed; defaulting empty", t);
            }
        }
        return OptionalInt.empty();
    }

    /**
     * The villager's current AI attack target, if any. Used to detect self-defense: if a villager is
     * already targeting the player, the player retaliating is lawful, not a crime (spec §5.2). An MCA
     * villager is a {@code Mob}, so {@code getTarget()} is valid; kept here so the cast never escapes.
     * Safe default: {@code empty}.
     */
    public static Optional<LivingEntity> getMcaTarget(Entity villager) {
        if (villager instanceof VillagerEntityMCA mca) {
            try {
                return Optional.ofNullable(mca.getTarget());
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
        if (entity instanceof VillagerEntityMCA && entity instanceof LivingEntity living) {
            try {
                return living.isSleeping();
            } catch (Throwable t) {
                McaCrime.LOGGER.debug("MCA isVillagerSleeping failed; defaulting false", t);
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ relationship / family graph (§8.5)
    // Signatures verified against the MCA Reborn 7.6.20 jar (forge.net.mca.*); runtime behavior is an
    // in-world ⚠ verification target. Every method fails safe so a differing MCA version degrades ransom to
    // the village-authority fallback rather than crashing.

    /**
     * One-time probe of whether MCA's relationship API is resolvable. When false, {@code RansomService}
     * skips the family payer tiers and uses the village-authority fallback only (spec §8.5). Cached.
     */
    public static boolean isRelationshipApiAvailable() {
        Boolean ok = relationshipApiOk;
        if (ok != null) {
            return ok;
        }
        try {
            Class.forName("forge.net.mca.entity.ai.relationship.EntityRelationship");
            Class.forName("forge.net.mca.server.world.data.FamilyTreeNode");
            ok = Boolean.TRUE;
        } catch (Throwable t) {
            McaCrime.LOGGER.warn("MCA relationship API unavailable; ransom will use the village-authority fallback only.");
            ok = Boolean.FALSE;
        }
        relationshipApiOk = ok;
        return ok;
    }

    /** The villager/player's spouse UUID (MCA partner), or empty. Safe default: empty. */
    public static Optional<UUID> getSpouseUuid(Entity entity) {
        try {
            return EntityRelationship.of(entity).flatMap(EntityRelationship::getPartnerUUID);
        } catch (Throwable t) {
            McaCrime.LOGGER.debug("MCA getSpouseUuid failed; defaulting empty", t);
            return Optional.empty();
        }
    }

    public static List<UUID> getParentUuids(Entity entity) {
        return relativeUuids(entity, FamilyTreeNode::streamParents);
    }

    public static List<UUID> getChildUuids(Entity entity) {
        return relativeUuids(entity, node -> node.children().stream());
    }

    public static List<UUID> getSiblingUuids(Entity entity) {
        return relativeUuids(entity, node -> node.siblings().stream());
    }

    /** UUIDs of relatives up to {@code generations} away (grandparents/grandchildren/etc.). Safe default: empty. */
    public static List<UUID> getCloseRelativeUuids(Entity entity, int generations) {
        return relativeUuids(entity, node -> node.getAllRelatives(generations));
    }

    private static List<UUID> relativeUuids(Entity entity, Function<FamilyTreeNode, Stream<UUID>> extractor) {
        try {
            return EntityRelationship.of(entity)
                    .map(EntityRelationship::getFamilyEntry)
                    .filter(Objects::nonNull)
                    .map(node -> extractor.apply(node).filter(Objects::nonNull).collect(Collectors.toList()))
                    .orElseGet(List::of);
        } catch (Throwable t) {
            McaCrime.LOGGER.debug("MCA relativeUuids failed; defaulting empty", t);
            return List.of();
        }
    }

    /**
     * Whether the entity is an adult (so adult-child ransom gating is correct, spec §8.5). Players/unknown
     * are treated as adults so a missing age never wrongly excludes a valid payer. Safe default: {@code true}.
     */
    public static boolean isAdult(Entity entity) {
        if (entity instanceof VillagerLike<?> villager) {
            try {
                return villager.getAgeState() == AgeState.ADULT;
            } catch (Throwable t) {
                McaCrime.LOGGER.debug("MCA isAdult failed; defaulting true", t);
            }
        }
        return true;
    }
}
