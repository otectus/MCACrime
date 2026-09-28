package dev.otectus.mcacrime.restraint;

import dev.otectus.mcacrime.detention.DetentionRecord;
import dev.otectus.mcacrime.network.CrimeNetwork;
import dev.otectus.mcacrime.network.PhysicalStateDeltaS2CPacket;
import dev.otectus.mcacrime.network.PhysicalStateRemoveS2CPacket;
import dev.otectus.mcacrime.network.PhysicalStateS2CPacket;
import dev.otectus.mcacrime.state.world.CrimeWorldData;
import dev.otectus.mcacrime.tether.TetherRecord;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Keeps clients' picture of who is physically restrained correct (§1.6, M1.6).
 *
 * <p>Full state on tracking start, reconnect and respawn; a delta on every change; an explicit
 * removal when a subject's state goes away. That is three messages rather than the one broadcast
 * {@code enforcement/RestraintSync} sends today, and each one exists because the others cannot say
 * what it says: a delta cannot tell a joining client about a prisoner it has never heard of, and the
 * absence of a delta cannot be distinguished from a release.
 *
 * <p>Sent to the people who can see the subject, not to everybody. Cuffs are something onlookers
 * look at; a server-wide broadcast would be a packet per restrained villager per client that will
 * never draw one, which is also how the old bulk sync leaked the whole prison roster to anybody who
 * logged in.
 *
 * <p>Server-side only, and deliberately not an event subscriber in this milestone: nothing in M1 is
 * reachable from gameplay, and the tracking/respawn hooks are wired in M2 when the legacy sync is
 * retired.
 */
public final class RestraintSyncService {

    private RestraintSyncService() {
    }

    /**
     * The view of one subject, resolved against the world tables.
     *
     * <p>The holder is projected as an <em>entity id</em> rather than a UUID because the rope is
     * drawn every frame and a client resolves an entity id in constant time. Ids are per level, so a
     * holder in another dimension simply resolves to nobody, which is the correct answer anyway.
     */
    public static PhysicalRestraintView view(@Nullable MinecraftServer server, CrimeWorldData data,
                                             PhysicalRestraintState state) {
        int holderEntityId = PhysicalRestraintView.NO_HOLDER;
        TetherRecord tether = state.tetherId() == null ? null : data.tether(state.tetherId());
        if (tether != null && tether.holder() != null && server != null) {
            Entity holder = findEntity(server, tether.holder());
            if (holder != null) {
                holderEntityId = holder.getId();
            }
        }
        DetentionRecord detention = state.detentionId() == null ? null : data.detention(state.detentionId());
        return PhysicalRestraintView.of(state, holderEntityId, detention != null);
    }

    /** Every subject with physical state right now, as views. */
    public static List<PhysicalRestraintView> snapshot(@Nullable MinecraftServer server,
                                                       CrimeWorldData data) {
        List<PhysicalRestraintView> views = new ArrayList<>();
        for (PhysicalRestraintState state : data.physicalRestraints()) {
            if (!state.vacant()) {
                views.add(view(server, data, state));
            }
        }
        return views;
    }

    /**
     * Sends one client the whole picture.
     *
     * <p>Whole rather than incremental on purpose: this is the message that runs on login, on
     * respawn and after a dimension change, and each of those is a moment when the client's copy may
     * be arbitrarily wrong. Bounded by {@code PacketBounds.MAX_PHYSICAL_SUBJECTS} on the wire.
     */
    public static void sendFullState(@Nullable ServerPlayer to, @Nullable CrimeWorldData data) {
        if (to == null || data == null) {
            return;
        }
        CrimeNetwork.sendPhysicalState(to, new PhysicalStateS2CPacket(snapshot(to.getServer(), data)));
    }

    /** Tells everybody tracking {@code subject} what changed about them. */
    public static void broadcastDelta(@Nullable Entity subject, @Nullable CrimeWorldData data) {
        if (subject == null || data == null) {
            return;
        }
        PhysicalRestraintState state = data.physicalRestraint(subject.getUUID());
        if (state == null) {
            broadcastRemoval(subject, 0L);
            return;
        }
        CrimeNetwork.broadcastPhysicalStateDelta(subject,
                new PhysicalStateDeltaS2CPacket(view(subject.getServer(), data, state)));
    }

    /** Tells everybody tracking {@code subject} to forget them. */
    public static void broadcastRemoval(@Nullable Entity subject, long revision) {
        if (subject == null) {
            return;
        }
        CrimeNetwork.broadcastPhysicalStateRemoval(subject,
                new PhysicalStateRemoveS2CPacket(subject.getUUID(), revision));
    }

    /**
     * Tells everybody to forget a subject who is not loaded anywhere.
     *
     * <p>The unloaded twin of {@link #broadcastRemoval(Entity, long)}: a villager released from a cell
     * in a sleeping chunk still has to stop being drawn in cuffs on every client that saw them. Sent
     * to the whole player list because there is no entity left to derive a tracking set from, and one
     * small packet at the end of a captivity is cheaper than leaving stale gear on screen.
     */
    public static void broadcastRemoval(@Nullable MinecraftServer server, @Nullable UUID subject) {
        if (server == null || subject == null) {
            return;
        }
        PhysicalStateRemoveS2CPacket packet = new PhysicalStateRemoveS2CPacket(subject, 0L);
        for (ServerPlayer viewer : server.getPlayerList().getPlayers()) {
            CrimeNetwork.sendPhysicalStateRemoval(viewer, packet);
        }
    }

    /** Sends one tracking client one subject's state, for the moment it starts tracking them. */
    public static void sendOnTrackingStart(@Nullable ServerPlayer to, @Nullable Entity subject,
                                           @Nullable CrimeWorldData data) {
        if (to == null || subject == null || data == null) {
            return;
        }
        PhysicalRestraintState state = data.physicalRestraint(subject.getUUID());
        if (state == null || state.vacant()) {
            return; // nothing to draw; silence is the correct message
        }
        CrimeNetwork.sendPhysicalStateDelta(to,
                new PhysicalStateDeltaS2CPacket(view(to.getServer(), data, state)));
    }

    /** The holder entity across every loaded level, or null. */
    @Nullable
    private static Entity findEntity(MinecraftServer server, UUID id) {
        for (net.minecraft.server.level.ServerLevel level : server.getAllLevels()) {
            Entity entity = level.getEntity(id);
            if (entity != null) {
                return entity;
            }
        }
        return null;
    }
}
