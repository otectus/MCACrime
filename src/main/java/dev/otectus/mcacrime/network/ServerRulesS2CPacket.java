package dev.otectus.mcacrime.network;

import dev.otectus.mcacrime.client.CrimeClientHandlers;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * Server to client: the few server settings a tooltip quotes as a number (0.8.0, protocol 21).
 *
 * <p>Today that is the key ring's capacity. The client's own COMMON file is not the one the server
 * enforces, so a ring tooltip that read it would state a client default as if it were the server's limit;
 * with this packet the tooltip says the server's number, and before it arrives it says the number is
 * the server's to set. Sent on login and re-sent after a config reload, like the weapon policy, and
 * display only: every limit is still checked on the server.
 */
public record ServerRulesS2CPacket(int maxKeysPerRing) {

    public static void encode(ServerRulesS2CPacket msg, FriendlyByteBuf buf) {
        buf.writeVarInt(msg.maxKeysPerRing);
    }

    public static ServerRulesS2CPacket decode(FriendlyByteBuf buf) {
        int capacity = buf.readVarInt();
        if (capacity < 0 || capacity > dev.otectus.mcacrime.locks.KeyRingBindings.MAX_CAPACITY) {
            throw new io.netty.handler.codec.DecoderException("key ring capacity " + capacity + " out of range");
        }
        return new ServerRulesS2CPacket(capacity);
    }

    /** The values as the server holds them right now. */
    public static ServerRulesS2CPacket fromConfig() {
        return new ServerRulesS2CPacket(dev.otectus.mcacrime.locks.LockInteractions.maxKeysPerRing());
    }

    public static void handle(ServerRulesS2CPacket msg, Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context context = ctx.get();
        context.setPacketHandled(true);
        if (!context.getDirection().getReceptionSide().isClient()) {
            return;
        }
        context.enqueueWork(() ->
                DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> CrimeClientHandlers.onServerRules(msg)));
    }
}
