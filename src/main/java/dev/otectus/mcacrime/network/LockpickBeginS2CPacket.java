package dev.otectus.mcacrime.network;

import dev.otectus.mcacrime.McaCrime;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * "Open the lockpicking dial": the server has a session and this is what to draw (M3.3).
 *
 * <p>Presentation only. The two profile numbers let the client animate at the right speed and the
 * phase target lets it draw the ghost pick — neither is authority, because the server runs the same
 * numbers itself and a client that lies about them only lies to its own display.
 *
 * <p>The drain divisor rides along because the server owns it and the client cannot read a COMMON
 * config value — that file is the player's own, not the server's. Sending it is what lets the bar
 * interpolate between packets at the rate the server is actually draining it.
 *
 * <p>There is deliberately no lock id, no block position and no subject: a picker does not need to be
 * told the identity of what they are picking, and a packet that carried it would be handing every
 * client the lock table one session at a time.
 *
 * @param sessionId          the session this dial belongs to
 * @param progressIncrease   meter gained per successful alignment
 * @param speedIncrease      the drain parameter, for the client's own interpolation
 * @param phase              the phase the session is on
 * @param targetMilliDegrees where the ghost pick sits this phase
 * @param meter              the current meter, 0..40
 * @param drainDivisor       the server's own {@code lockpicking.drainPerTickDivisor}
 */
public record LockpickBeginS2CPacket(long sessionId, int progressIncrease, int speedIncrease, int phase,
                                     int targetMilliDegrees, int meter, int drainDivisor)
        implements CustomPacketPayload {

    public static final Type<LockpickBeginS2CPacket> TYPE = new Type<>(McaCrime.id("lockpick_begin"));

    /**
     * Hand-written rather than composed: {@code StreamCodec.composite} stops at six components and
     * this payload has seven. The field order below <em>is</em> the wire order, so it must match the
     * record's declaration order exactly.
     */
    public static final StreamCodec<RegistryFriendlyByteBuf, LockpickBeginS2CPacket> STREAM_CODEC =
            new StreamCodec<>() {
                @Override
                public LockpickBeginS2CPacket decode(RegistryFriendlyByteBuf buf) {
                    return new LockpickBeginS2CPacket(buf.readLong(), buf.readVarInt(), buf.readVarInt(),
                            buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt());
                }

                @Override
                public void encode(RegistryFriendlyByteBuf buf, LockpickBeginS2CPacket payload) {
                    buf.writeLong(payload.sessionId());
                    buf.writeVarInt(payload.progressIncrease());
                    buf.writeVarInt(payload.speedIncrease());
                    buf.writeVarInt(payload.phase());
                    buf.writeVarInt(payload.targetMilliDegrees());
                    buf.writeVarInt(payload.meter());
                    buf.writeVarInt(payload.drainDivisor());
                }
            };

    public LockpickBeginS2CPacket {
        drainDivisor = Math.max(1, drainDivisor);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
