package dev.otectus.mcacrime.network;

import dev.otectus.mcacrime.client.CrimeClientHandlers;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

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
 * @param sessionId         the session this dial belongs to
 * @param progressIncrease  meter gained per successful alignment
 * @param speedIncrease     the drain parameter, for the client's own interpolation
 * @param phase             the phase the session is on
 * @param targetMilliDegrees where the ghost pick sits this phase
 * @param meter             the current meter, 0..40
 * @param drainDivisor      the server's own {@code lockpicking.drainPerTickDivisor}
 */
public record LockpickBeginS2CPacket(long sessionId, int progressIncrease, int speedIncrease, int phase,
                                     int targetMilliDegrees, int meter, int drainDivisor) {

    public static void encode(LockpickBeginS2CPacket msg, FriendlyByteBuf buf) {
        buf.writeLong(msg.sessionId);
        buf.writeVarInt(msg.progressIncrease);
        buf.writeVarInt(msg.speedIncrease);
        buf.writeVarInt(msg.phase);
        buf.writeVarInt(msg.targetMilliDegrees);
        buf.writeVarInt(msg.meter);
        buf.writeVarInt(msg.drainDivisor);
    }

    public static LockpickBeginS2CPacket decode(FriendlyByteBuf buf) {
        return new LockpickBeginS2CPacket(buf.readLong(), buf.readVarInt(), buf.readVarInt(),
                buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), Math.max(1, buf.readVarInt()));
    }

    public static void handle(LockpickBeginS2CPacket msg, Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context context = ctx.get();
        context.setPacketHandled(true);
        if (!context.getDirection().getReceptionSide().isClient()) {
            return;
        }
        context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> CrimeClientHandlers.onLockpickBegin(msg)));
    }
}
