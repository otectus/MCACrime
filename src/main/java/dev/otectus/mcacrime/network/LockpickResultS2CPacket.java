package dev.otectus.mcacrime.network;

import dev.otectus.mcacrime.McaCrime;
import dev.otectus.mcacrime.lockpick.LockpickOutcome;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * How the session ended. Server to client, and only ever in that direction (M3.3).
 *
 * <p>The direction is the entire point of the class. Upstream's equivalent is bidirectional and its
 * <em>client</em> decides the outcome, which is why a modified client there can open any lock and free
 * any prisoner. Here the outcome has already happened by the time this is sent; the packet closes a
 * screen.
 *
 * <p>The outcome travels through {@link CrimeStreamCodecs#enumCodec}, which <b>refuses</b> an ordinal
 * no build ever wrote rather than reading it as a cancellation — this line's settled policy, matching
 * the baseline's {@code PacketBounds.readEnumOrdinal}.
 */
public record LockpickResultS2CPacket(long sessionId, LockpickOutcome outcome)
        implements CustomPacketPayload {

    public static final Type<LockpickResultS2CPacket> TYPE = new Type<>(McaCrime.id("lockpick_result"));

    public static final StreamCodec<RegistryFriendlyByteBuf, LockpickResultS2CPacket> STREAM_CODEC =
            StreamCodec.composite(
                    CrimeStreamCodecs.LONG, LockpickResultS2CPacket::sessionId,
                    CrimeStreamCodecs.enumCodec(LockpickOutcome.class, "lockpick outcome"),
                    LockpickResultS2CPacket::outcome,
                    LockpickResultS2CPacket::new);

    public LockpickResultS2CPacket {
        if (outcome == null) {
            throw new IllegalArgumentException("A lockpick result must name an outcome");
        }
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
