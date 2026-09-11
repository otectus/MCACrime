package dev.otectus.mcacrime.network;

import dev.otectus.mcacrime.McaCrime;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import java.util.UUID;

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
                                 String releaseConditionKey) implements CustomPacketPayload {

    /** A villager display name is player-facing text, not the 32767 an unbounded write would allow. */
    public static final int MAX_NAME_LENGTH = 64;
    /** The two keys are namespaced ids looked up on the client, never shown raw. */
    public static final int MAX_KEY_LENGTH = 128;

    public static final Type<BailQuoteS2CPacket> TYPE = new Type<>(McaCrime.id("bail_quote"));

    /** Eight fields, beyond what {@code StreamCodec.composite} carries, so written by hand. */
    public static final StreamCodec<RegistryFriendlyByteBuf, BailQuoteS2CPacket> STREAM_CODEC =
            StreamCodec.of(BailQuoteS2CPacket::write, BailQuoteS2CPacket::read);

    public BailQuoteS2CPacket {
        relativeName = relativeName == null ? "" : relativeName;
        offenceKey = offenceKey == null ? "" : offenceKey;
        releaseConditionKey = releaseConditionKey == null ? "" : releaseConditionKey;
        cost = Math.max(0L, cost);
        remainingTicks = Math.max(0L, remainingTicks);
    }

    private static void write(RegistryFriendlyByteBuf buf, BailQuoteS2CPacket msg) {
        UUIDUtil.STREAM_CODEC.encode(buf, msg.menuId());
        ByteBufCodecs.VAR_INT.encode(buf, msg.menuRevision());
        UUIDUtil.STREAM_CODEC.encode(buf, msg.targetId());
        buf.writeVarLong(msg.cost());
        ByteBufCodecs.stringUtf8(MAX_NAME_LENGTH).encode(buf, msg.relativeName());
        ByteBufCodecs.stringUtf8(MAX_KEY_LENGTH).encode(buf, msg.offenceKey());
        buf.writeVarLong(msg.remainingTicks());
        ByteBufCodecs.stringUtf8(MAX_KEY_LENGTH).encode(buf, msg.releaseConditionKey());
    }

    private static BailQuoteS2CPacket read(RegistryFriendlyByteBuf buf) {
        return new BailQuoteS2CPacket(UUIDUtil.STREAM_CODEC.decode(buf),
                ByteBufCodecs.VAR_INT.decode(buf), UUIDUtil.STREAM_CODEC.decode(buf), buf.readVarLong(),
                ByteBufCodecs.stringUtf8(MAX_NAME_LENGTH).decode(buf),
                ByteBufCodecs.stringUtf8(MAX_KEY_LENGTH).decode(buf), buf.readVarLong(),
                ByteBufCodecs.stringUtf8(MAX_KEY_LENGTH).decode(buf));
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
