package dev.otectus.mcacrime.network;

import dev.otectus.mcacrime.McaCrime;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import org.jetbrains.annotations.Nullable;

/**
 * The server's answer about which style one viewer's Mask Station has selected (0.7.2 §8.1, §8.4).
 *
 * <p>A payload rather than a data slot because a recipe id does not fit in one: §8.1 is explicit that
 * a resource id must never be compressed into a truncating integer transfer. The generation travels
 * the other way, as an ordinary data slot, because an int is exactly what that mechanism is for.
 *
 * <p>Sent when the selection changes and whenever the server refuses one, so a stale client is either
 * corrected or told plainly — it can never be shown a different mask than the one it chose merely
 * because the catalogue resorted.
 *
 * <p>A cleared selection is a present boolean and no id, never a sentinel id: an invented
 * {@code mcacrime:none} would be indistinguishable from a recipe a pack happened to call that.
 */
public record MaskSelectionS2CPacket(int containerId, @Nullable ResourceLocation recipeId, int generation)
        implements CustomPacketPayload {

    public static final Type<MaskSelectionS2CPacket> TYPE = new Type<>(McaCrime.id("mask_selection"));

    public static final StreamCodec<RegistryFriendlyByteBuf, MaskSelectionS2CPacket> STREAM_CODEC =
            StreamCodec.of(MaskSelectionS2CPacket::write, MaskSelectionS2CPacket::read);

    private static void write(RegistryFriendlyByteBuf buf, MaskSelectionS2CPacket msg) {
        buf.writeVarInt(msg.containerId);
        buf.writeBoolean(msg.recipeId != null);
        if (msg.recipeId != null) {
            buf.writeUtf(msg.recipeId.toString(), PacketBounds.MAX_ID_LENGTH);
        }
        buf.writeVarInt(msg.generation);
    }

    private static MaskSelectionS2CPacket read(RegistryFriendlyByteBuf buf) {
        int containerId = buf.readVarInt();
        ResourceLocation recipeId = buf.readBoolean() ? PacketBounds.readResourceLocation(buf) : null;
        return new MaskSelectionS2CPacket(containerId, recipeId, buf.readVarInt());
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
