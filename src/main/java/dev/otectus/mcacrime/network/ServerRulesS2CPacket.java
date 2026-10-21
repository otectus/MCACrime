package dev.otectus.mcacrime.network;

import dev.otectus.mcacrime.McaCrime;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Server to client: the few server settings a tooltip quotes as a number (0.8.0, protocol 21).
 *
 * <p>Today that is the key ring's capacity. The client's own COMMON file is not the one the server
 * enforces, so a ring tooltip that read it would state a client default as if it were the server's limit;
 * with this payload the tooltip says the server's number, and before it arrives it says the number is
 * the server's to set. Sent on login and re-sent after a config reload, like the weapon policy, and
 * display only: every limit is still checked on the server.
 */
public record ServerRulesS2CPacket(int maxKeysPerRing) implements CustomPacketPayload {

    public static final Type<ServerRulesS2CPacket> TYPE = new Type<>(McaCrime.id("server_rules"));

    public static final StreamCodec<ByteBuf, ServerRulesS2CPacket> STREAM_CODEC = ByteBufCodecs.VAR_INT.map(
            capacity -> {
                if (capacity < 0 || capacity > dev.otectus.mcacrime.locks.KeyRingBindings.MAX_CAPACITY) {
                    throw new io.netty.handler.codec.DecoderException("key ring capacity " + capacity + " out of range");
                }
                return new ServerRulesS2CPacket(capacity);
            }, ServerRulesS2CPacket::maxKeysPerRing);

    /** The values as the server holds them right now. */
    public static ServerRulesS2CPacket fromConfig() {
        return new ServerRulesS2CPacket(dev.otectus.mcacrime.locks.LockInteractions.maxKeysPerRing());
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
