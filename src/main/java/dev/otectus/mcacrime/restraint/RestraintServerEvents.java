package dev.otectus.mcacrime.restraint;

import dev.otectus.mcacrime.McaCrime;
import dev.otectus.mcacrime.captivity.CustodyService;
import dev.otectus.mcacrime.compat.TownsteadBridge;
import dev.otectus.mcacrime.compat.TownsteadLifeStageView;
import dev.otectus.mcacrime.job.CriminalJob;
import dev.otectus.mcacrime.job.WorldCriminalJobService;
import dev.otectus.mcacrime.network.CrimeNetwork;
import dev.otectus.mcacrime.state.world.CrimeWorldData;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.server.ServerLifecycleHooks;

import java.util.UUID;

/**
 * The server hooks the physical restraint engine needs, in one place (0.7.5 M2.11).
 *
 * <p>Two jobs, and both used to belong to classes the legacy engine owned. The tick is what
 * {@code captivity/CaptureTicker} was: it no longer drives a capture channel, because an application
 * is decided when it is attempted, but it still expires sessions and still advances NPC captivities
 * on the same batched one-second cadence. The tracking hook is what {@code enforcement/RestraintSync}
 * did: a client that walks into range of a prisoner it has never heard of is told what is on them,
 * what body it is being drawn on, and — riding along on the same event, as before — whether the
 * villager it is looking at has a criminal job.
 */
@Mod.EventBusSubscriber(modid = McaCrime.MOD_ID)
public final class RestraintServerEvents {

    /**
     * How often NPC captivities are advanced. One second, batched: the captivity cap is measured in
     * minutes, so crediting twenty ticks once beats crediting one tick twenty times over a table that
     * has to be walked each pass.
     */
    private static final int NPC_CUSTODY_INTERVAL_TICKS = 20;

    private static int npcCustodyCounter;

    private RestraintServerEvents() {
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) {
            return;
        }
        RestraintService.serverTick(server);
        // Lockpick sessions drain on the server clock rather than on a client's frame rate, which is
        // the whole reinterpretation of §3.5: a slow machine must not make a lock easier.
        dev.otectus.mcacrime.lockpick.LockpickService.serverTick(server);
        if (++npcCustodyCounter >= NPC_CUSTODY_INTERVAL_TICKS) {
            npcCustodyCounter = 0;
            CustodyService.tickNpcCaptives(server, NPC_CUSTODY_INTERVAL_TICKS);
            creditTimeRestrained(server);
            // Famine, Shroud, Exhaust and Silence, and the Imbue reverse index, from one walk of the
            // restraint table rather than a world scan per point of damage (M6.1).
            dev.otectus.mcacrime.enchantment.RestraintEffects.tick(server, CrimeWorldData.get(server),
                    NPC_CUSTODY_INTERVAL_TICKS);
        }
    }

    /**
     * Credits the time-spent-restrained statistics (M5.11).
     *
     * <p>On the same one-second batch as the NPC captivity tick and for the same reason: the counter
     * is measured in ticks, so crediting twenty at once is one map walk instead of twenty. One credit
     * per occupied slot, because wearing cuffs and a hood is two things being worn and Appendix A
     * counts them separately.
     *
     * <p>Online players only. A villager has no statistics screen, and an offline player is not
     * spending time in anything.
     */
    private static void creditTimeRestrained(MinecraftServer server) {
        CrimeWorldData data = CrimeWorldData.get(server);
        if (data == null) {
            return;
        }
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            PhysicalRestraintState state = data.physicalRestraint(player.getUUID());
            if (state == null || state.vacant()) {
                continue;
            }
            for (dev.otectus.mcacrime.restraint.RestraintSlot slot
                    : dev.otectus.mcacrime.restraint.RestraintSlot.values()) {
                state.slot(slot).ifPresent(worn -> dev.otectus.mcacrime.stat.CrimeStats.awardRestraint(
                        player, worn.definitionId(),
                        dev.otectus.mcacrime.stat.CrimeStatIds.Kind.TIME_SPENT_RESTRAINED,
                        NPC_CUSTODY_INTERVAL_TICKS));
            }
        }
    }

    /**
     * A client just came within tracking range of somebody, so tell it what they are wearing.
     *
     * <p>One packet when the answer is anything at all, and nothing when it is nothing — which is
     * almost always. This is what makes a villager who has been sitting cuffed in a cell for an hour
     * appear correctly to somebody who has only just walked in.
     */
    @SubscribeEvent
    public static void onStartTracking(PlayerEvent.StartTracking event) {
        if (!(event.getEntity() instanceof ServerPlayer viewer)) {
            return;
        }
        Entity target = event.getTarget();
        MinecraftServer server = viewer.getServer();
        if (server == null || target == null) {
            return;
        }
        CrimeWorldData data = CrimeWorldData.get(server);
        PhysicalRestraintState state = data.physicalRestraint(target.getUUID());
        if (state != null && !state.vacant()) {
            RestraintSyncService.sendOnTrackingStart(viewer, target, data);
            // The rig rides with the gear. It is only ever consulted to draw a restraint, so the one
            // moment it becomes interesting is the moment one appears.
            CrimeNetwork.sendRestraintRig(viewer, target.getUUID(), rigHumanoid(target), rigId(target));
        }
        sendCriminalJob(viewer, server, target.getUUID());
    }

    /**
     * Whether this subject's settlement rig has arms a restraint can sit on.
     *
     * <p>Resolved here, on the server, because this is the only side where it can be: the Townstead
     * bridge binds at {@code ServerStartedEvent}, so a multiplayer client asking the same question
     * gets "humanoid" for everybody. Unknown answers "humanoid" for the same reason the client
     * fallback does — a wrong yes is a cosmetic oddity on an unusual body, a wrong no replaces the
     * cuffs with a band for every ordinary villager on the server.
     */
    private static boolean rigHumanoid(Entity subject) {
        if (!(subject instanceof LivingEntity living)) {
            return true;
        }
        try {
            return TownsteadBridge.lifeStage(living).asOptional()
                    .map(TownsteadLifeStageView::humanoidRig)
                    .orElse(true);
        } catch (Throwable ignored) {
            return true;
        }
    }

    /** The rig id itself, for the client's own diagnostics. Empty when the stage overrode nothing. */
    private static String rigId(Entity subject) {
        if (!(subject instanceof LivingEntity living)) {
            return "";
        }
        try {
            return TownsteadBridge.lifeStage(living).asOptional()
                    .map(TownsteadLifeStageView::rig)
                    .orElse("");
        } catch (Throwable ignored) {
            return "";
        }
    }

    /**
     * Rides along on the same tracking event: a client that can see a villager also needs to know
     * whether that villager is a fence, because the Crime button enables for one without a weapon.
     *
     * <p>Here rather than in a handler of its own because the trigger is identical and a second
     * subscriber to the same event would only ever be one refactor away from disagreeing with this
     * one.
     */
    private static void sendCriminalJob(ServerPlayer viewer, MinecraftServer server, UUID subject) {
        CriminalJob job = WorldCriminalJobService.of(server).get(subject);
        if (job != CriminalJob.NONE) {
            CrimeNetwork.sendCriminalJob(viewer, subject, job);
        }
    }
}
