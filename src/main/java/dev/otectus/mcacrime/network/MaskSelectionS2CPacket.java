package dev.otectus.mcacrime.network;

import dev.otectus.mcacrime.client.CrimeClientHandlers;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import javax.annotation.Nullable;
import java.util.function.Supplier;

/**
 * The server's answer about which style one viewer's Mask Station has selected (0.7.2 §8.1, §8.4).
 *
 * <p>A packet rather than a data slot because a recipe id does not fit in one: §8.1 is explicit that
 * a resource id must never be compressed into a truncating integer transfer. The generation travels
 * the other way, as an ordinary data slot, because an int is exactly what that mechanism is for.
 *
 * <p>Sent when the selection changes and whenever the server refuses one, so a stale client is either
 * corrected or told plainly — it can never be shown a different mask than the one it chose merely
 * because the catalogue resorted.
 */
public record MaskSelectionS2CPacket(int containerId, @Nullable ResourceLocation recipeId, int generation) {

    public static void encode(MaskSelectionS2CPacket msg, FriendlyByteBuf buf) {
        buf.writeVarInt(msg.containerId);
        buf.writeBoolean(msg.recipeId != null);
        if (msg.recipeId != null) {
            buf.writeUtf(msg.recipeId.toString(), PacketBounds.MAX_ID_LENGTH);
        }
        buf.writeVarInt(msg.generation);
    }

    public static MaskSelectionS2CPacket decode(FriendlyByteBuf buf) {
        int containerId = buf.readVarInt();
        ResourceLocation recipeId = buf.readBoolean() ? PacketBounds.readResourceLocation(buf) : null;
        return new MaskSelectionS2CPacket(containerId, recipeId, buf.readVarInt());
    }

    public static void handle(MaskSelectionS2CPacket msg, Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context context = ctx.get();
        context.setPacketHandled(true);
        if (!context.getDirection().getReceptionSide().isClient()) {
            return;
        }
        context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> CrimeClientHandlers.onMaskSelection(msg)));
    }
}
