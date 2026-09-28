package dev.otectus.mcacrime.network;

import dev.otectus.mcacrime.client.CrimeClientHandlers;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

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
public record LockpickPhaseS2CPacket(long sessionId, int phase, int targetMilliDegrees, int meter) {

    public static void encode(LockpickPhaseS2CPacket msg, FriendlyByteBuf buf) {
        buf.writeLong(msg.sessionId);
        buf.writeVarInt(msg.phase);
        buf.writeVarInt(msg.targetMilliDegrees);
        buf.writeVarInt(msg.meter);
    }

    public static LockpickPhaseS2CPacket decode(FriendlyByteBuf buf) {
        return new LockpickPhaseS2CPacket(buf.readLong(), buf.readVarInt(), buf.readVarInt(),
                buf.readVarInt());
    }

    public static void handle(LockpickPhaseS2CPacket msg, Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context context = ctx.get();
        context.setPacketHandled(true);
        if (!context.getDirection().getReceptionSide().isClient()) {
            return;
        }
        context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> CrimeClientHandlers.onLockpickPhase(msg)));
    }
}
