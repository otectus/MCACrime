package dev.otectus.mcacrime.network;

import dev.otectus.mcacrime.frisk.FriskingService;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * "Take that one" (M5.2, §1.6, spec §11.2).
 *
 * <p>Four bounded numbers and nothing else. No actor — identity is the connection. No subject — the
 * session was pinned to one when the screen opened. No item id, no NBT, no outcome, no destination,
 * no block position. The slot is a position in the server's own ordered view, so the worst a forged
 * index can name is another slot of the same subject in the same session, and the revision check
 * then decides whether that slot is the one the searcher was actually looking at.
 *
 * @param sessionId        the search the client believes it is in
 * @param viewIndex        which of the session's slots
 * @param expectedRevision the revision the searcher was shown for it
 * @param count            how many, clamped on arrival and again against the live stack
 */
public record FriskTransferC2SPacket(long sessionId, int viewIndex, int expectedRevision, int count) {

    public FriskTransferC2SPacket {
        viewIndex = Math.max(0, Math.min(PacketBounds.MAX_FRISK_SLOTS - 1, viewIndex));
        count = Math.max(1, Math.min(PacketBounds.MAX_FRISK_COUNT, count));
    }

    public static void encode(FriskTransferC2SPacket msg, FriendlyByteBuf buf) {
        buf.writeLong(msg.sessionId);
        buf.writeVarInt(msg.viewIndex);
        buf.writeInt(msg.expectedRevision);
        buf.writeVarInt(msg.count);
    }

    public static FriskTransferC2SPacket decode(FriendlyByteBuf buf) {
        return new FriskTransferC2SPacket(buf.readLong(), buf.readVarInt(), buf.readInt(),
                buf.readVarInt());
    }

    public static void handle(FriskTransferC2SPacket msg, Supplier<NetworkEvent.Context> ctx) {
        ServerPacketGuard.accept(ctx, RequestBudget.Category.ACTION,
                sender -> FriskingService.transfer(sender, msg.sessionId, msg.viewIndex,
                        msg.expectedRevision, msg.count));
    }
}
