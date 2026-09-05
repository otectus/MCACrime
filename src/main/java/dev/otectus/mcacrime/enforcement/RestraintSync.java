package dev.otectus.mcacrime.enforcement;

import dev.otectus.mcacrime.McaCrime;
import dev.otectus.mcacrime.captivity.CustodyRecord;
import dev.otectus.mcacrime.network.CrimeNetwork;
import dev.otectus.mcacrime.job.CriminalJob;
import dev.otectus.mcacrime.job.WorldCriminalJobService;
import dev.otectus.mcacrime.state.world.CrimeWorldData;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Tells every client who is restrained and who is holding them.
 *
 * <p>Broadcast from {@link ArrestStates} and {@code CustodyService}, which are the only writers of the
 * underlying state, so the rendered picture cannot drift from the authoritative one — there is no
 * second place a restraint could start or end without a packet going out.
 *
 * <p>Three routes reach a client, and all three build their payload from {@link
 * RestraintVisualResolver} rather than reading the state themselves: a broadcast on change, a bulk
 * snapshot on login, and a single send when a client starts tracking an already-restrained entity.
 * That last one is what makes a villager who has been sitting cuffed in a cell for an hour appear
 * correctly to somebody who has only just walked into range.
 *
 * <p>Everything here is presentation. The server decides restraint; this only says so out loud.
 */
@Mod.EventBusSubscriber(modid = McaCrime.MOD_ID)
public final class RestraintSync {

    private RestraintSync() {
    }

    /** Broadcasts one subject's restraint state to everybody who might be looking at them. */
    public static void broadcast(Entity subject) {
        if (subject == null) {
            return;
        }
        MinecraftServer server = subject.getServer();
        if (server == null) {
            return;
        }
        CrimeNetwork.broadcastRestraint(subject.getUUID(),
                RestraintVisualResolver.resolve(server, subject));
    }

    /**
     * Broadcasts for a subject that may not be loaded — a captive whose chunk is asleep still has a
     * record, and releasing it has to clear the cuffs on every client that saw them.
     */
    public static void broadcast(MinecraftServer server, UUID subject) {
        if (server == null || subject == null) {
            return;
        }
        CrimeNetwork.broadcastRestraint(subject, RestraintVisualResolver.resolve(server, subject));
    }

    /** Sends a joining client the full picture, so a mid-arrest connection is not missing the cuffs. */
    public static void syncOnLogin(ServerPlayer to) {
        MinecraftServer server = to == null ? null : to.getServer();
        if (server == null) {
            return;
        }
        Map<UUID, RestraintVisualState> snapshot = new HashMap<>();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            RestraintVisualState state = RestraintVisualResolver.resolve(server, player);
            if (state.restrained()) {
                snapshot.put(player.getUUID(), state);
            }
        }
        // NPC captives are in the world data whether or not they are loaded, and a cell full of
        // prisoners is exactly the case a login snapshot exists for.
        for (CustodyRecord record : CrimeWorldData.get(server).custodyRecords()) {
            if (record.isCaptivePlayer()) {
                continue;
            }
            RestraintVisualState state = RestraintVisualResolver.resolve(server, record.getCaptive());
            if (state.restrained()) {
                snapshot.put(record.getCaptive(), state);
            }
        }
        CrimeNetwork.sendRestraintBulk(to, snapshot);
    }

    /**
     * A client just came within tracking range of an entity, so tell it if that entity is restrained.
     *
     * <p>The bulk snapshot only covers what was restrained at login; everything restrained since, or
     * loaded since, arrives here. One packet to one player when the answer is yes, and nothing at all
     * when it is no — which is almost always.
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
        RestraintVisualState state = RestraintVisualResolver.resolve(server, target);
        if (state.restrained()) {
            CrimeNetwork.sendRestraintTo(viewer, target.getUUID(), state);
        }
        sendCriminalJob(viewer, server, target.getUUID());
    }

    /**
     * Rides along on the same tracking event: a client that can see a villager also needs to know
     * whether that villager is a fence, because the Crime button enables for one without a weapon.
     *
     * <p>Here rather than in a handler of its own because the trigger is identical and a second
     * subscriber to the same event would only ever be one refactor away from disagreeing with this one.
     */
    private static void sendCriminalJob(ServerPlayer viewer, MinecraftServer server, UUID subject) {
        CriminalJob job = WorldCriminalJobService.of(server).get(subject);
        if (job != CriminalJob.NONE) {
            CrimeNetwork.sendCriminalJob(viewer, subject, job);
        }
    }
}
