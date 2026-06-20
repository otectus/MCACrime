package dev.otectus.mcacrime.enforcement;

import dev.otectus.mcacrime.McaCrimeConfig;
import dev.otectus.mcacrime.compat.McaCompat;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.AABB;

/**
 * Makes nearby (non-guard) MCA villagers flee a Red player (spec §4.1). Best-effort and fail-safe — paths
 * villagers away via vanilla navigation through {@link McaCompat#makeVillagerFlee}; gated by
 * {@code enableVillagerFlee}. Trade/dialogue refusal is interaction-time and deferred.
 */
public final class VillagerReaction {

    private VillagerReaction() {
    }

    public static void fleeFrom(ServerPlayer player) {
        if (!McaCrimeConfig.COMMON.enableVillagerFlee.get()) {
            return;
        }
        if (!(player.level() instanceof ServerLevel level)) {
            return;
        }
        double radius = McaCrimeConfig.COMMON.villagerFleeRadius.get();
        AABB box = player.getBoundingBox().inflate(radius);
        for (LivingEntity villager : level.getEntitiesOfClass(LivingEntity.class, box,
                e -> McaCompat.isMcaVillager(e) && !McaCompat.isGuard(e))) {
            McaCompat.makeVillagerFlee(villager, player);
        }
    }
}
