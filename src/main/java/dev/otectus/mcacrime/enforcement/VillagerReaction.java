package dev.otectus.mcacrime.enforcement;

import dev.otectus.mcacrime.McaCrimeConfig;
import dev.otectus.mcacrime.compat.McaCompat;
import dev.otectus.mcacrime.memory.OffenderMemory;
import dev.otectus.mcacrime.state.world.CrimeWorldData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.AABB;

/**
 * Drives short, victim-specific panic. Long-term fear and negative hearts affect dialogue/relationship,
 * not a permanent navigation override on every villager near a globally Red player.
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
        long now = level.getGameTime();
        CrimeWorldData world = CrimeWorldData.get(level.getServer());
        for (LivingEntity villager : level.getEntitiesOfClass(LivingEntity.class, box,
                e -> McaCompat.isMcaVillager(e) && !McaCompat.isGuard(e))) {
            OffenderMemory memory = world.villagerProfile(villager.getUUID())
                    .map(profile -> profile.offenderMemories().get(player.getUUID())).orElse(null);
            if (memory != null && now < memory.panicUntil()) McaCompat.makeVillagerFlee(villager, player);
        }
    }
}
