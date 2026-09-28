package dev.otectus.mcacrime.network;

import dev.otectus.mcacrime.client.CrimeClientHandlers;
import dev.otectus.mcacrime.lockpick.LockpickOutcome;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * How the session ended. Server to client, and only ever in that direction (M3.3).
 *
 * <p>The direction is the entire point of the class. Upstream's equivalent is bidirectional and its
 * <em>client</em> decides the outcome, which is why a modified client there can open any lock and free
 * any prisoner. Here the outcome has already happened by the time this is sent; the packet closes a
 * screen.
 */
public record LockpickResultS2CPacket(long sessionId, LockpickOutcome outcome) {

    public LockpickResultS2CPacket {
        if (outcome == null) {
            throw new IllegalArgumentException("A lockpick result must name an outcome");
        }
    }

    public static void encode(LockpickResultS2CPacket msg, FriendlyByteBuf buf) {
        buf.writeLong(msg.sessionId);
        PacketBounds.writeEnumOrdinal(buf, msg.outcome);
    }

    /** An unknown outcome is refused rather than read as a cancellation. One rule for every enum. */
    public static LockpickResultS2CPacket decode(FriendlyByteBuf buf) {
        long sessionId = buf.readLong();
        LockpickOutcome outcome = PacketBounds.readEnumOrdinal(buf, LockpickOutcome.class)
                .orElseThrow(() -> new io.netty.handler.codec.DecoderException("Unknown lockpick outcome"));
        return new LockpickResultS2CPacket(sessionId, outcome);
    }

    public static void handle(LockpickResultS2CPacket msg, Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context context = ctx.get();
        context.setPacketHandled(true);
        if (!context.getDirection().getReceptionSide().isClient()) {
            return;
        }
        context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> CrimeClientHandlers.onLockpickResult(msg)));
    }
}
