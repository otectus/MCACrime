package dev.otectus.mcacrime.network;

import dev.otectus.mcacrime.McaCrime;
import dev.otectus.mcacrime.crime.Band;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Server→client: the receiving player's own karma/heat/band/wanted/jail/legal-target, for the reputation player card. */
public record SelfStatusS2CPacket(long karma, long heat, Band band, boolean wanted,
                                  long jailRemainingTicks, boolean legalTarget) implements CustomPacketPayload {

    public static final Type<SelfStatusS2CPacket> TYPE = new Type<>(McaCrime.id("self_status"));

    public static final StreamCodec<RegistryFriendlyByteBuf, SelfStatusS2CPacket> STREAM_CODEC =
            StreamCodec.composite(
                    CrimeStreamCodecs.LONG, SelfStatusS2CPacket::karma,
                    CrimeStreamCodecs.LONG, SelfStatusS2CPacket::heat,
                    CrimeStreamCodecs.enumCodec(Band.class, "band"), SelfStatusS2CPacket::band,
                    ByteBufCodecs.BOOL, SelfStatusS2CPacket::wanted,
                    CrimeStreamCodecs.LONG, SelfStatusS2CPacket::jailRemainingTicks,
                    ByteBufCodecs.BOOL, SelfStatusS2CPacket::legalTarget,
                    SelfStatusS2CPacket::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
