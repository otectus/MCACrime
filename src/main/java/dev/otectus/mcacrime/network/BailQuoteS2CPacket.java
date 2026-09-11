package dev.otectus.mcacrime.network;

import dev.otectus.mcacrime.client.CrimeClientHandlers;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.UUID;
import java.util.function.Supplier;

/**
 * The price of bailing one relative out, answered to the player who asked and to nobody else.
 *
 * <p>Sent in response to the first {@code pay_bail} click rather than pushed: the cost depends on how
 * much sentence is left and on how many times that relative has been arrested before, both of which
 * are server facts that change while the menu is open. Quoting on the server and showing the quote is
 * what stops the client ever computing a price the server would disagree with.
 *
 * <p>It carries the menu id and revision it was quoted against so the screen's Pay button can send the
 * ordinary {@link StartActionC2SPacket} back through the same validated menu session. Nothing here is
 * authoritative: the server re-prices and re-checks custody when that packet arrives.
 */
public record BailQuoteS2CPacket(UUID menuId, int menuRevision, UUID targetId, long cost,
                                 String relativeName, String offenceKey, long remainingTicks,
                                 String releaseConditionKey) {

    public BailQuoteS2CPacket {
        relativeName = relativeName == null ? "" : relativeName;
        offenceKey = offenceKey == null ? "" : offenceKey;
        releaseConditionKey = releaseConditionKey == null ? "" : releaseConditionKey;
        cost = Math.max(0L, cost);
        remainingTicks = Math.max(0L, remainingTicks);
    }

    public static void encode(BailQuoteS2CPacket msg, FriendlyByteBuf buf) {
        buf.writeUUID(msg.menuId);
        buf.writeVarInt(msg.menuRevision);
        buf.writeUUID(msg.targetId);
        buf.writeVarLong(msg.cost);
        // A villager display name is player-facing text; the two keys are looked up, not shown raw.
        buf.writeUtf(msg.relativeName, PacketBounds.MAX_DISPLAY_LENGTH);
        buf.writeUtf(msg.offenceKey, PacketBounds.MAX_ID_LENGTH);
        buf.writeVarLong(msg.remainingTicks);
        buf.writeUtf(msg.releaseConditionKey, PacketBounds.MAX_ID_LENGTH);
    }

    public static BailQuoteS2CPacket decode(FriendlyByteBuf buf) {
        return new BailQuoteS2CPacket(buf.readUUID(), buf.readVarInt(), buf.readUUID(), buf.readVarLong(),
                buf.readUtf(PacketBounds.MAX_DISPLAY_LENGTH), PacketBounds.readId(buf),
                buf.readVarLong(), PacketBounds.readId(buf));
    }

    public static void handle(BailQuoteS2CPacket msg, Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context context = ctx.get();
        context.setPacketHandled(true);
        if (!context.getDirection().getReceptionSide().isClient()) {
            return;
        }
        context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> CrimeClientHandlers.onBailQuote(msg)));
    }
}
