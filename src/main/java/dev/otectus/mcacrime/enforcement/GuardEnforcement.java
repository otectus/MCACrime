package dev.otectus.mcacrime.enforcement;

import dev.otectus.mcacrime.McaCrime;
import dev.otectus.mcacrime.McaCrimeConfig;
import dev.otectus.mcacrime.compat.McaCompat;
import dev.otectus.mcacrime.event.AmbientMessages;
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
    private static final java.util.Map<java.util.UUID, Long> CHALLENGED_AT = new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.Map<java.util.UUID, Alert> ALERTS = new java.util.concurrent.ConcurrentHashMap<>();
    private static final long SURRENDER_WINDOW_TICKS = 60L;
    private record Alert(long until, String reasonKey) {}

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
        CHALLENGED_AT.keySet().removeIf(id -> server.getPlayerList().getPlayer(id) == null);
        long serverTime = server.overworld().getGameTime();
        ALERTS.entrySet().removeIf(entry -> entry.getValue().until() < serverTime);
        double radius = McaCrimeConfig.COMMON.guardAggroRadius.get();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            try {
                if (!(player.level() instanceof ServerLevel level)) {
                    continue;
                }
                Alert alert = ALERTS.get(player.getUUID());
                boolean legalTarget = LegalTarget.isLegalTarget(player);
                if (legalTarget || alert != null) {
                    AABB box = player.getBoundingBox().inflate(radius);
                    boolean targeted = false;
                    long now = level.getGameTime();
                    java.util.List<LivingEntity> guards = level.getEntitiesOfClass(LivingEntity.class, box, McaCompat::isGuard);
                    if (!guards.isEmpty()) {
                        Long challenged = CHALLENGED_AT.putIfAbsent(player.getUUID(), now);
                        if (challenged == null) {
                            player.sendSystemMessage(net.minecraft.network.chat.Component.translatable("mcacrime.guard.challenge"));
                        } else if (now - challenged >= SURRENDER_WINDOW_TICKS) {
                            for (LivingEntity guard : guards) {
                            if (McaCompat.setGuardTarget(guard, player)) {
                                targeted = true;
                            }
                        }
                        }
                    }
                    if (targeted) {
                        AmbientMessages.noteGuardAggro(player, legalTarget
                                ? LegalTarget.primaryReasonKey(player) : alert.reasonKey());
                    }
                } else {
                    CHALLENGED_AT.remove(player.getUUID());
                    AABB box = player.getBoundingBox().inflate(radius);
                    for (LivingEntity guard : level.getEntitiesOfClass(LivingEntity.class, box, McaCompat::isGuard)) {
                        McaCompat.clearGuardTarget(guard, player);
                    }
                }
                // The reaction service now filters to this player's direct victims and their bounded
                // panic timers, so it must run regardless of the player's global band.
                VillagerReaction.fleeFrom(player);
            } catch (Throwable t) {
                McaCrime.LOGGER.debug("guard/villager enforcement failed for a player; continuing", t);
            }
        }
    }

    /** A direct victim/witness report gives nearby guards a bounded basis to challenge this player. */
    public static void alert(ServerPlayer player, String reasonKey, long durationTicks) {
        long now = player.level().getGameTime();
        ALERTS.put(player.getUUID(), new Alert(now + Math.max(1L, durationTicks), reasonKey));
    }
}
