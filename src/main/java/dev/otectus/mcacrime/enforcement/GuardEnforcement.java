package dev.otectus.mcacrime.enforcement;

import dev.otectus.mcacrime.McaCrime;
import dev.otectus.mcacrime.McaCrimeConfig;
import dev.otectus.mcacrime.compat.McaCompat;
import dev.otectus.mcacrime.crime.Band;
import dev.otectus.mcacrime.engine.CrimeState;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.server.ServerLifecycleHooks;

/**
 * Drives guard pursuit of Legal-Target players and villager flee from Red players (spec §4, §4.4), on a
 * throttled server tick. The scan is bounded by player count (only online Legal-Target / Red players cost
 * anything) and a radius query — never a per-tick world scan (spec §20). Every MCA call is fail-safe: a
 * differing MCA version just makes the scan a no-op, never a crash.
 */
@Mod.EventBusSubscriber(modid = McaCrime.MOD_ID)
public final class GuardEnforcement {

    private static int counter;

    private GuardEnforcement() {
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        int interval = Math.max(1, McaCrimeConfig.COMMON.guardScanIntervalTicks.get());
        if (++counter < interval) {
            return;
        }
        counter = 0;

        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) {
            return;
        }
        double radius = McaCrimeConfig.COMMON.guardAggroRadius.get();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            try {
                if (!(player.level() instanceof ServerLevel level)) {
                    continue;
                }
                if (LegalTarget.isLegalTarget(player)) {
                    AABB box = player.getBoundingBox().inflate(radius);
                    for (LivingEntity guard : level.getEntitiesOfClass(LivingEntity.class, box, McaCompat::isGuard)) {
                        McaCompat.setGuardTarget(guard, player); // re-applied each scan (MCA's sensor reclaims it)
                    }
                }
                if (CrimeState.getBand(player) == Band.RED) {
                    VillagerReaction.fleeFrom(player);
                }
            } catch (Throwable t) {
                McaCrime.LOGGER.debug("guard/villager enforcement failed for a player; continuing", t);
            }
        }
    }
}
