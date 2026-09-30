package dev.otectus.mcacrime.network;

import dev.otectus.mcacrime.McaCrime;
import dev.otectus.mcacrime.crime.Band;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.neoforged.neoforge.network.codec.NeoForgeStreamCodecs;

/**
 * Server→client: the receiving player's own karma/heat/band/wanted/jail/legal-target, for the reputation
 * player card.
 *
 * <p>{@code jailPaused} is true while an escaped prisoner's sentence is frozen. The server stops the
 * clock the moment they escape; without the flag the client kept counting its local copy down to zero
 * and showed a sentence expiring that was in fact standing still.
 */
public record SelfStatusS2CPacket(long karma, long heat, Band band, boolean wanted,
                                  long jailRemainingTicks, boolean jailPaused, boolean legalTarget)
        implements CustomPacketPayload {

    public static final Type<SelfStatusS2CPacket> TYPE = new Type<>(McaCrime.id("self_status"));

    public static final StreamCodec<RegistryFriendlyByteBuf, SelfStatusS2CPacket> STREAM_CODEC =
            // Seven fields: one past vanilla's StreamCodec.composite, so NeoForge's seven-field overload.
            NeoForgeStreamCodecs.composite(
                    CrimeStreamCodecs.LONG, SelfStatusS2CPacket::karma,
                    CrimeStreamCodecs.LONG, SelfStatusS2CPacket::heat,
                    CrimeStreamCodecs.enumCodec(Band.class, "band"), SelfStatusS2CPacket::band,
                    ByteBufCodecs.BOOL, SelfStatusS2CPacket::wanted,
                    CrimeStreamCodecs.LONG, SelfStatusS2CPacket::jailRemainingTicks,
                    ByteBufCodecs.BOOL, SelfStatusS2CPacket::jailPaused,
                    ByteBufCodecs.BOOL, SelfStatusS2CPacket::legalTarget,
                    SelfStatusS2CPacket::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
