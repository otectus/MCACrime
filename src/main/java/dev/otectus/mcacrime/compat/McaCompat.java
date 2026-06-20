package dev.otectus.mcacrime.compat;

import dev.otectus.mcacrime.McaCrime;
import forge.net.mca.entity.VillagerEntityMCA;
import forge.net.mca.entity.VillagerLike;
import forge.net.mca.entity.ai.Memories;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;

import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;

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
}
