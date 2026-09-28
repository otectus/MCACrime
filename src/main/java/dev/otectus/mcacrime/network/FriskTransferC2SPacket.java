package dev.otectus.mcacrime.network;

import dev.otectus.mcacrime.McaCrime;
import dev.otectus.mcacrime.frisk.FriskingService;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * "Take that one" (M5.2, §1.6, spec §11.2).
 *
 * <p>Four bounded numbers and nothing else. No actor — identity is the connection. No subject — the
 * session was pinned to one when the screen opened. No item id, no components, no outcome, no
 * destination, no block position. The slot is a position in the server's own ordered view, so the
 * worst a forged index can name is another slot of the same subject in the same session, and the
 * revision check then decides whether that slot is the one the searcher was actually looking at.
 *
 * @param sessionId        the search the client believes it is in
 * @param viewIndex        which of the session's slots
 * @param expectedRevision the revision the searcher was shown for it
 * @param count            how many, clamped on arrival and again against the live stack
 */
public record FriskTransferC2SPacket(long sessionId, int viewIndex, int expectedRevision, int count)
        implements CustomPacketPayload {

    public static final Type<FriskTransferC2SPacket> TYPE = new Type<>(McaCrime.id("frisk_transfer"));

    public static final StreamCodec<RegistryFriendlyByteBuf, FriskTransferC2SPacket> STREAM_CODEC =
            StreamCodec.composite(
                    CrimeStreamCodecs.LONG, FriskTransferC2SPacket::sessionId,
                    ByteBufCodecs.VAR_INT, FriskTransferC2SPacket::viewIndex,
                    ByteBufCodecs.INT, FriskTransferC2SPacket::expectedRevision,
                    ByteBufCodecs.VAR_INT, FriskTransferC2SPacket::count,
                    FriskTransferC2SPacket::new);

    public FriskTransferC2SPacket {
        viewIndex = Math.max(0, Math.min(PacketBounds.MAX_FRISK_SLOTS - 1, viewIndex));
        count = Math.max(1, Math.min(PacketBounds.MAX_FRISK_COUNT, count));
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(FriskTransferC2SPacket msg, IPayloadContext ctx) {
        ServerPacketGuard.accept(ctx, RequestBudget.Category.ACTION,
                sender -> FriskingService.transfer(sender, msg.sessionId, msg.viewIndex,
                        msg.expectedRevision, msg.count));
    }
}
