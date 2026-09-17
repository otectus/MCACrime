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
    public static void onLivingTick(net.neoforged.neoforge.event.tick.EntityTickEvent.Pre event) {
        if (!(event.getEntity().level() instanceof ServerLevel)) {
            return;
        }
        if (event.getEntity() instanceof net.minecraft.world.entity.LivingEntity living) {
            NpcAwareness.settleSleeping(living);
        }
        // Whose tick this is, for the Townstead hooks that are handed a Brain or a PathNavigation and
        // no villager at all. Recorded here rather than from a handler of its own because this event
        // already fires for every entity on the server and a second subscriber would pay the dispatch
        // again for nothing. EntityTickEvent.Pre fires from ServerLevel.tickNonPassenger immediately
        // before Entity.tick(), so the context is in place well before Townstead's tickers run from
        // the TAIL of aiStep.
        //
        // Villager-only because nothing else can be a Townstead villager -- MCA's villager extends
        // net.minecraft.world.entity.npc.Villager -- and TownsteadTickContext itself does nothing at
        // all until a Townstead mixin has actually been applied.
        if (event.getEntity() instanceof net.minecraft.world.entity.npc.Villager villager) {
            dev.otectus.mcacrime.compat.TownsteadTickContext.observe(villager);
        }
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        MinecraftServer server = net.neoforged.neoforge.server.ServerLifecycleHooks.getCurrentServer();
        if (server == null) {
            return;
        }
        long now = server.overworld().getGameTime();
        // Before anything reads a claim, not after: activity claims lapse rather than being cleared,
        // and a consumer that saw an expired one would be steering a villager nobody owns. Costs one
        // isEmpty() check on a world where MCA: Crime is doing nothing, which is nearly every tick.
        dev.otectus.mcacrime.activity.CrimeActivityRegistry.sweep(now);

        CrimeReactionService.tick(server);
        dev.otectus.mcacrime.enforcement.GuardChallengeService.holdConversations(server);
        dev.otectus.mcacrime.memory.WitnessSocialService.tick(server);

        if (now - lastPrune >= PRUNE_INTERVAL_TICKS) {
            lastPrune = now;
            ReportService.prune(server, now);
        }

        // Every entity in every level has finished ticking by the time ServerTickEvent.Post fires, so
        // whatever villager the context still names is finished with. Dropping it here means a
        // Townstead hook that somehow ran outside an entity tick reads "no context" and leaves the
        // villager alone, instead of acting on whoever happened to be last.
        dev.otectus.mcacrime.compat.TownsteadTickContext.clear();
    }

    /** Drops every controller on shutdown, so a restart never inherits a stale reaction. */
    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        CrimeReactionService.clearAll(event.getServer());
        // The two memory-only enforcement caches ride along here for the same reason: an enforcement
        // hold and an escort both describe the current few seconds, and a restart has no business
        // inheriting either.
        LawHold.clearAll();
        dev.otectus.mcacrime.activity.CrimeActivityRegistry.clearAll();
        // Derived Townstead state goes with it: every entry describes a villager in a world that is
        // no longer loaded, and a second world in one session must read the companion again.
        dev.otectus.mcacrime.compat.TownsteadSnapshotCache.clearAll();
        dev.otectus.mcacrime.compat.TownsteadTickContext.clear();
        // Work-tool provenance too: every entry names a display copy held by an entity in a world that
        // is being unloaded, and a second world in one session must be told again which stack is a prop.
        dev.otectus.mcacrime.compat.TownsteadEquipmentProvenance.clearAll();
        dev.otectus.mcacrime.enforcement.EscortService.clearAll();
        // The custody-care interval table is the same shape of state: it names captives in a world that
        // is unloading, and losing it costs one extra needs check on the next boot.
        dev.otectus.mcacrime.captivity.CustodyCareService.clearAll();
        // Village cooldowns too. Losing them costs each village one extra evaluation on the next boot,
        // and that evaluation is idempotent: the pass counts guards from the live world and never
        // converts one back.
        dev.otectus.mcacrime.enforcement.GuardPopulationService.clearAll();
        dev.otectus.mcacrime.enforcement.GuardEnforcement.clearAll();
        // The civic layer's two memory-only pieces. The contracts themselves are persisted and must
        // survive; what goes is the sweep clock and the cached settlement economy profiles, both of
        // which describe a world that is unloading and are re-derived on the next boot.
        dev.otectus.mcacrime.civic.CivicWorkHandlers.clearAll();
        dev.otectus.mcacrime.economy.EconomyProfileResolver.clearAll();
        lastPrune = 0L;
        dev.otectus.mcacrime.memory.WitnessSocialService.clear();
    }
}
