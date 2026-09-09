package dev.otectus.mcacrime.ai;

import dev.otectus.mcacrime.McaCrime;
import dev.otectus.mcacrime.enforcement.LawHold;
import dev.otectus.mcacrime.memory.ReportService;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
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
@Mod.EventBusSubscriber(modid = McaCrime.MOD_ID)
public final class CrimeReactionTicker {

    /** One online minute between statute sweeps. */
    private static final long PRUNE_INTERVAL_TICKS = 1200L;

    private static long lastPrune;

    private CrimeReactionTicker() {
    }

    @SubscribeEvent
    public static void onLivingTick(net.minecraftforge.event.entity.living.LivingEvent.LivingTickEvent event) {
        if (event.getEntity().level() instanceof ServerLevel
                && event.getEntity().isSleeping()) {
            NpcAwareness.settleSleeping(event.getEntity());
        }
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        MinecraftServer server = net.minecraftforge.server.ServerLifecycleHooks.getCurrentServer();
        if (server == null) {
            return;
        }
        CrimeReactionService.tick(server);
        dev.otectus.mcacrime.enforcement.GuardChallengeService.holdConversations(server);
        dev.otectus.mcacrime.memory.WitnessSocialService.tick(server);

        long now = server.overworld().getGameTime();
        if (now - lastPrune >= PRUNE_INTERVAL_TICKS) {
            lastPrune = now;
            ReportService.prune(server, now);
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
        dev.otectus.mcacrime.memory.WitnessSocialService.clear();
    }
}
