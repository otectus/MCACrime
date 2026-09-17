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
        if (!(event.getEntity().level() instanceof ServerLevel)) {
            return;
        }
        if (event.getEntity().isSleeping()) {
            NpcAwareness.settleSleeping(event.getEntity());
        }
        // Whose tick this is, for the Townstead hooks that are handed a Brain or a PathNavigation and
        // no villager at all. Recorded here rather than from a handler of its own because this event
        // already fires for every living entity on the server and a second subscriber would pay the
        // dispatch again for nothing. LivingTickEvent fires at the start of LivingEntity.tick(), so
        // the context is in place well before Townstead's tickers run from the TAIL of aiStep.
        //
        // Villager-only because nothing else can be a Townstead villager -- MCA's villager extends
        // net.minecraft.world.entity.npc.Villager -- and TownsteadTickContext itself does nothing at
        // all until a Townstead mixin has actually been applied.
        if (event.getEntity() instanceof net.minecraft.world.entity.npc.Villager) {
            dev.otectus.mcacrime.compat.TownsteadTickContext.observe(event.getEntity());
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

        // Every entity in every level has finished ticking by the END phase, so whatever villager the
        // context still names is finished with. Dropping it here means a Townstead hook that somehow
        // ran outside an entity tick reads "no context" and leaves the villager alone, instead of
        // acting on whoever happened to be last.
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
        lastPrune = 0L;
        dev.otectus.mcacrime.memory.WitnessSocialService.clear();
    }
}
