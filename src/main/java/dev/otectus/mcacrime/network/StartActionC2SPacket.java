package dev.otectus.mcacrime.network;

import dev.otectus.mcacrime.action.CrimeActionService;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.network.NetworkEvent;

import java.util.UUID;
import java.util.function.Supplier;

public record StartActionC2SPacket(UUID nonce, UUID menuId, int menuRevision,
                                   ResourceLocation actionId, UUID targetId) {
    public static void encode(StartActionC2SPacket msg, FriendlyByteBuf buf) {
        buf.writeUUID(msg.nonce); buf.writeUUID(msg.menuId); buf.writeVarInt(msg.menuRevision);
        buf.writeResourceLocation(msg.actionId); buf.writeUUID(msg.targetId);
    }
    public static StartActionC2SPacket decode(FriendlyByteBuf buf) {
        return new StartActionC2SPacket(buf.readUUID(), buf.readUUID(), buf.readVarInt(),
                PacketBounds.readResourceLocation(buf), buf.readUUID());
    }
    public static void handle(StartActionC2SPacket msg, Supplier<NetworkEvent.Context> ctx) {
        ServerPacketGuard.accept(ctx, RequestBudget.Category.ACTION,
                sender -> CrimeActionService.startFromMenu(sender, msg));
    }
}
