package dev.otectus.mcacrime.civic;

import dev.otectus.mcacrime.McaCrime;
import dev.otectus.mcacrime.api.event.BountyResolvedEvent;
import dev.otectus.mcacrime.api.event.VictimCrimeMemoryChangedEvent;
import dev.otectus.mcacrime.api.model.VictimMemoryView;
import dev.otectus.mcacrime.bounty.BountyResolution;
import dev.otectus.mcacrime.bounty.BountyResolutionType;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Where civic work is credited from: things MCA: Crime already watched happening.
 *
 * <h2>Events, never a scan</h2>
 *
 * <p>Reference §12.1 point 4 asks for work measured by "actual accepted outputs", and the only honest
 * reading of that on a server is: something MCA: Crime independently decided had happened. So every
 * handler here hangs off a transition the mod already publishes and already considers authoritative —
 * a bounty it paid, an apology it accepted — and passes it to {@link CivicWorkService#credit} with the
 * identity of the thing that happened as the dedupe key.
 *
 * <p>There is deliberately no proximity check, no "is the player inside the village" test and no per
 * tick contract scan. The only periodic work is {@link CivicWorkService#sweep}, which walks the
 * contract table — bounded at 512 — every five seconds to lapse expired contracts and keep an NPC's
 * activity claim alive.
 *
 * <p>Property returns are credited from {@code PropertyTheftService} instead of from here, because the
 * restitution path is not an event: it is a matched container transfer, already attributed, and the
 * one place that knows a specific lot came back is the method that marks its receipt restored.
 */
@Mod.EventBusSubscriber(modid = McaCrime.MOD_ID)
public final class CivicWorkHandlers {

    /** How often the contract table is swept. Five seconds; contracts are measured in game days. */
    public static final int SWEEP_INTERVAL_TICKS = 100;

    private static long nextSweep;

    private CivicWorkHandlers() {
    }

    /**
     * Bringing in an outlaw counts towards a patrol-assist contract.
     *
     * <p>Alive only. {@link BountyResolutionType#KILLED} is excluded because §12.1 asks for a useful
     * task and the whole point of the contract is that it is an alternative to punishment, not a
     * licence to hunt; a settlement that paid off a fine for a killing would be running a worse justice
     * system than the one it replaced.
     */
    @SubscribeEvent
    public static void onBountyResolved(BountyResolvedEvent event) {
        if (!CivicWorkService.enabled()) {
            return;
        }
        BountyResolution resolution = event.getResolution();
        if (resolution == null || resolution.claimant() == null
                || resolution.resolutionType() == BountyResolutionType.KILLED) {
            return;
        }
        MinecraftServer server = net.minecraftforge.server.ServerLifecycleHooks.getCurrentServer();
        if (server == null) {
            return;
        }
        CivicWorkService.credit(server, resolution.claimant(), CivicTask.GUARD_ASSIST_PATROL,
                "bounty:" + resolution.claimKey().asKey(), 1);
    }

    /**
     * An accepted apology counts towards a making-amends contract.
     *
     * <p>Gated on the reason string the memory service stamps, so an ordinary memory update — a repeat
     * offence, a decay pass, a sentence served — credits nothing. The dedupe key is the villager plus
     * the memory's own key, so apologising to the same villager for the same category twice counts
     * once and the player has to face somebody else.
     */
    @SubscribeEvent
    public static void onMemoryChanged(VictimCrimeMemoryChangedEvent event) {
        if (!CivicWorkService.enabled() || !"apology".equals(event.getReason())) {
            return;
        }
        VictimMemoryView memory = event.getMemory();
        if (memory == null || memory.perpetrator() == null || !memory.apologized()) {
            return;
        }
        MinecraftServer server = net.minecraftforge.server.ServerLifecycleHooks.getCurrentServer();
        if (server == null) {
            return;
        }
        CivicWorkService.credit(server, memory.perpetrator(), CivicTask.VICTIM_AMENDS,
                "apology:" + event.getVillager() + ":" + memory.category(), 1);
    }

    /** Lapses expired contracts and renews the claims on NPCs currently on duty. */
    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !CivicWorkService.enabled()) {
            return;
        }
        MinecraftServer server = event.getServer();
        if (server == null) {
            return;
        }
        long now = server.overworld().getGameTime();
        if (now < nextSweep) {
            return;
        }
        nextSweep = now + SWEEP_INTERVAL_TICKS;
        CivicWorkService.sweep(server);
    }

    /** Resets the sweep clock. Server stop, and tests. */
    public static void clearAll() {
        nextSweep = 0L;
    }
}
