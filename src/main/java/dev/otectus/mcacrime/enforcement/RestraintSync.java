package dev.otectus.mcacrime.enforcement;

import dev.otectus.mcacrime.network.CrimeNetwork;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Tells every client who is restrained and who is holding them.
 *
 * <p>Broadcast from {@link ArrestStates}, which is the only writer of the phase, so the rendered state
 * cannot drift from the authoritative one — there is no second place a restraint could start or end
 * without a packet going out.
 *
 * <p>Everything here is presentation. The server decides restraint; this only says so out loud.
 */
public final class RestraintSync {

    private RestraintSync() {
    }

    /** Broadcasts one player's restraint state to everybody who might be looking at them. */
    public static void broadcast(ServerPlayer player) {
        if (player == null || player.getServer() == null) {
            return;
        }
        boolean restrained = ArrestStates.isRestrained(player);
        CrimeNetwork.broadcastRestraint(player.getUUID(), restrained,
                restrained ? guardEntityId(player) : -1);
    }

    /** Sends a joining client the full picture, so a mid-arrest connection is not missing the cuffs. */
    public static void syncOnLogin(ServerPlayer to) {
        MinecraftServer server = to == null ? null : to.getServer();
        if (server == null) {
            return;
        }
        Map<UUID, Integer> snapshot = new HashMap<>();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (ArrestStates.isRestrained(player)) {
                snapshot.put(player.getUUID(), guardEntityId(player));
            }
        }
        CrimeNetwork.sendRestraintBulk(to, snapshot);
    }

    /**
     * The entity id of the guard holding this player, or {@code -1}.
     *
     * <p>Resolved server-side rather than sending the guard's UUID, so the client never has to scan the
     * level for it every frame the rope is drawn.
     */
    private static int guardEntityId(ServerPlayer player) {
        ArrestState state = ArrestStates.of(player);
        UUID guard = state == null ? null : state.getGuard();
        if (guard == null || !(player.level() instanceof ServerLevel level)) {
            return -1;
        }
        Entity entity = level.getEntity(guard);
        return entity == null ? -1 : entity.getId();
    }
}
