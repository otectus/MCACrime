package dev.otectus.mcacrime.network;

import dev.otectus.mcacrime.McaCrime;
import dev.otectus.mcacrime.crime.Band;
import dev.otectus.mcacrime.engine.CrimeState;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

import java.util.Map;
import java.util.UUID;

/**
 * Our own Forge {@link SimpleChannel} (independent of MCA's network, spec §13/§18). All sync is
 * server→client and <b>display-only</b>: the client never sends state changes, and the server never
 * trusts a client packet to mutate Karma/Heat. Registered during common setup.
 */
public final class CrimeNetwork {

    private static final String PROTOCOL_VERSION = "1";

    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            new ResourceLocation(McaCrime.MOD_ID, "main"),
            () -> PROTOCOL_VERSION,
            PROTOCOL_VERSION::equals,
            PROTOCOL_VERSION::equals);

    private static int nextId = 0;

    private CrimeNetwork() {
    }

    public static void register() {
        CHANNEL.registerMessage(nextId++, SelfStatusS2CPacket.class,
                SelfStatusS2CPacket::encode, SelfStatusS2CPacket::decode, SelfStatusS2CPacket::handle);
        CHANNEL.registerMessage(nextId++, BandSyncS2CPacket.class,
                BandSyncS2CPacket::encode, BandSyncS2CPacket::decode, BandSyncS2CPacket::handle);
        CHANNEL.registerMessage(nextId++, BandBulkSyncS2CPacket.class,
                BandBulkSyncS2CPacket::encode, BandBulkSyncS2CPacket::decode, BandBulkSyncS2CPacket::handle);
    }

    /** Pushes the player's own card data (karma/heat/band/wanted) to their client. */
    public static void sendSelfStatus(ServerPlayer player) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), new SelfStatusS2CPacket(
                CrimeState.getKarma(player),
                CrimeState.getHeat(player),
                CrimeState.getBand(player),
                CrimeState.isWanted(player)));
    }

    /** Broadcasts one player's band to every client (for nameplate coloring). */
    public static void broadcastBand(UUID subject, Band band) {
        CHANNEL.send(PacketDistributor.ALL.noArg(), new BandSyncS2CPacket(subject, band));
    }

    /** Sends a full snapshot of every online player's band to one joining client. */
    public static void sendBandBulk(ServerPlayer to, Map<UUID, Band> bands) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> to), new BandBulkSyncS2CPacket(bands));
    }
}
