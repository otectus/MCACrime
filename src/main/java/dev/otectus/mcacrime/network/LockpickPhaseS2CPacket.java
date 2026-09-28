package dev.otectus.mcacrime.network;

import dev.otectus.mcacrime.McaCrime;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * The next phase, and where the meter actually is (M3.3).
 *
 * <p>Sent after every scored attempt, hit or miss. The meter is included on a miss too, because the
 * client's interpolated drain is only an approximation of the server's and letting it drift away
 * unanswered is how a player ends up watching a full bar lose.
 *
 * @param sessionId          the session
 * @param phase              the phase now in progress
 * @param targetMilliDegrees the new ghost-pick target
 * @param meter              the server's meter, 0..40
 */
public record LockpickPhaseS2CPacket(long sessionId, int phase, int targetMilliDegrees, int meter)
        implements CustomPacketPayload {

    public static final Type<LockpickPhaseS2CPacket> TYPE = new Type<>(McaCrime.id("lockpick_phase"));

    public static final StreamCodec<RegistryFriendlyByteBuf, LockpickPhaseS2CPacket> STREAM_CODEC =
            StreamCodec.composite(
                    CrimeStreamCodecs.LONG, LockpickPhaseS2CPacket::sessionId,
                    ByteBufCodecs.VAR_INT, LockpickPhaseS2CPacket::phase,
                    ByteBufCodecs.VAR_INT, LockpickPhaseS2CPacket::targetMilliDegrees,
                    ByteBufCodecs.VAR_INT, LockpickPhaseS2CPacket::meter,
                    LockpickPhaseS2CPacket::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
