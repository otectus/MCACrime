package dev.otectus.mcacrime.network;

import dev.otectus.mcacrime.McaCrime;
import dev.otectus.mcacrime.crime.Band;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Server→client: a full snapshot of every online player's band, sent to a joining client. */
public record BandBulkSyncS2CPacket(Map<UUID, Band> bands) implements CustomPacketPayload {

    /**
     * Hard ceiling on the snapshot (spec §9.6). One entry per online player, so this sits far above any
     * real player list; the point is that the count is refused before anything is allocated, which the
     * Forge build's unbounded {@code readMap} did not do.
     */
    public static final int MAX_PLAYERS = 1024;

    public static final Type<BandBulkSyncS2CPacket> TYPE = new Type<>(McaCrime.id("band_bulk_sync"));

    public static final StreamCodec<RegistryFriendlyByteBuf, BandBulkSyncS2CPacket> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.map(HashMap::new, UUIDUtil.STREAM_CODEC,
                            CrimeStreamCodecs.enumCodec(Band.class, "band"), MAX_PLAYERS),
                    BandBulkSyncS2CPacket::bands,
                    BandBulkSyncS2CPacket::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
