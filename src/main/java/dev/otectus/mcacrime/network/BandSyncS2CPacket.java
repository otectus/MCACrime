package dev.otectus.mcacrime.network;

import dev.otectus.mcacrime.McaCrime;
import dev.otectus.mcacrime.crime.Band;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import java.util.UUID;

/** Server→client: a single player's band changed (for non-destructive nameplate coloring). */
public record BandSyncS2CPacket(UUID player, Band band) implements CustomPacketPayload {

    public static final Type<BandSyncS2CPacket> TYPE = new Type<>(McaCrime.id("band_sync"));

    public static final StreamCodec<RegistryFriendlyByteBuf, BandSyncS2CPacket> STREAM_CODEC =
            StreamCodec.composite(
                    UUIDUtil.STREAM_CODEC, BandSyncS2CPacket::player,
                    CrimeStreamCodecs.enumCodec(Band.class, "band"), BandSyncS2CPacket::band,
                    BandSyncS2CPacket::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
