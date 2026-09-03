package dev.otectus.mcacrime.network;

import dev.otectus.mcacrime.McaCrime;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Server→client: the receiving player's own captivity status (spec §10.3) — whether they are held, whether
 * it is lawful (jail) or a kidnapping, their captor's name, and the remaining real-time captivity cap. Sent
 * only on custody transitions and login (not the high-frequency self-status), and display-only.
 */
public record CaptiveStatusS2CPacket(boolean captive, boolean lawful, String captor,
                                     long capRemainingTicks) implements CustomPacketPayload {

    /** A captor is a player name, so this field never needs the 32767 the unbounded write allowed. */
    public static final int MAX_CAPTOR_LENGTH = 64;

    public static final Type<CaptiveStatusS2CPacket> TYPE = new Type<>(McaCrime.id("captive_status"));

    public static final StreamCodec<RegistryFriendlyByteBuf, CaptiveStatusS2CPacket> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.BOOL, CaptiveStatusS2CPacket::captive,
                    ByteBufCodecs.BOOL, CaptiveStatusS2CPacket::lawful,
                    ByteBufCodecs.stringUtf8(MAX_CAPTOR_LENGTH), CaptiveStatusS2CPacket::captor,
                    CrimeStreamCodecs.LONG, CaptiveStatusS2CPacket::capRemainingTicks,
                    CaptiveStatusS2CPacket::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
