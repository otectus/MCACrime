package dev.otectus.mcacrime.ai;

import dev.otectus.mcacrime.McaCrime;
import dev.otectus.mcacrime.enforcement.LawHold;
import dev.otectus.mcacrime.memory.ReportService;
import net.minecraft.server.MinecraftServer;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.minecraft.server.level.ServerLevel;

/**
 * Drives the reaction state machine and ages out stale observations.
 *
 * <p>The tick body is a single call into {@link CrimeReactionService}, which walks only the controllers
 * that exist. With nothing happening in the world this handler costs one {@code isEmpty()} check per
 * tick, which is the whole reason reactions are event-created rather than scanned for.
 *
 * <p>Observation pruning runs on a slow interval rather than every tick because a statute measured in
 * days does not need checking twenty times a second.
 */
@EventBusSubscriber(modid = McaCrime.MOD_ID)
public final class CrimeReactionTicker {

    /** One online minute between statute sweeps. */
    private static final long PRUNE_INTERVAL_TICKS = 1200L;

    private static long lastPrune;

    private CrimeReactionTicker() {
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        MinecraftServer server = net.neoforged.neoforge.server.ServerLifecycleHooks.getCurrentServer();
        if (server == null) {
            return;
        }
        CrimeReactionService.tick(server);

        long now = server.overworld().getGameTime();
        if (now - lastPrune >= PRUNE_INTERVAL_TICKS) {
            lastPrune = now;
            ReportService.prune(server, now);
        }
    }

    /**
     * Ends a reaction when its villager dies. Without this the controller would survive until the next
     * tick discovered the entity was gone — harmless, but it would also leave the death cleanup racing
     * a controller that still thinks it owns the villager's navigation.
     */
    @SubscribeEvent
    public static void onDeath(LivingDeathEvent event) {
        if (event.getEntity().level() instanceof ServerLevel level) {
            CrimeReactionService.clear(level, event.getEntity().getUUID());
        }
    }

    /** Drops every controller on shutdown, so a restart never inherits a stale reaction. */
    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        CrimeReactionService.clearAll(event.getServer());
        // The two memory-only enforcement caches ride along here for the same reason: an enforcement
        // hold and an escort both describe the current few seconds, and a restart has no business
        // inheriting either.
        LawHold.clearAll();
        dev.otectus.mcacrime.enforcement.EscortService.clearAll();
        // Village cooldowns too. Losing them costs each village one extra evaluation on the next boot,
        // and that evaluation is idempotent: the pass counts guards from the live world and never
        // converts one back.
        dev.otectus.mcacrime.enforcement.GuardPopulationService.clearAll();
        lastPrune = 0L;
    }
}
