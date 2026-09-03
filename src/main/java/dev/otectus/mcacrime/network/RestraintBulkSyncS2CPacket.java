package dev.otectus.mcacrime.network;

import dev.otectus.mcacrime.McaCrime;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Server to client: every restrained player right now, sent to a joining client.
 *
 * <p>The twin of {@code BandBulkSyncS2CPacket}, and for the same reason: a client that connects
 * halfway through somebody else's arrest would otherwise see an uncuffed player walking beside a guard
 * until the next state change happened to broadcast.
 */
public record RestraintBulkSyncS2CPacket(Map<UUID, Integer> restrained) implements CustomPacketPayload {

    /** The same hard ceiling the band snapshot uses, and for the same reason (spec §9.6). */
    public static final int MAX_PLAYERS = 1024;

    public static final Type<RestraintBulkSyncS2CPacket> TYPE =
            new Type<>(McaCrime.id("restraint_bulk_sync"));

    public static final StreamCodec<RegistryFriendlyByteBuf, RestraintBulkSyncS2CPacket> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.map(HashMap::new, UUIDUtil.STREAM_CODEC, ByteBufCodecs.VAR_INT, MAX_PLAYERS),
                    RestraintBulkSyncS2CPacket::restrained,
                    RestraintBulkSyncS2CPacket::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
