package dev.otectus.mcacrime.network;

import dev.otectus.mcacrime.action.CrimeActionService;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.UUID;
import java.util.function.Supplier;

public record RequestActionMenuC2SPacket(UUID targetId) {
    public static void encode(RequestActionMenuC2SPacket msg, FriendlyByteBuf buf) { buf.writeUUID(msg.targetId); }
    public static RequestActionMenuC2SPacket decode(FriendlyByteBuf buf) { return new RequestActionMenuC2SPacket(buf.readUUID()); }
    public static void handle(RequestActionMenuC2SPacket msg, Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context context = ctx.get();
        ServerPlayer sender = context.getSender();
        if (sender != null) context.enqueueWork(() -> CrimeActionService.openMenu(sender, msg.targetId));
        context.setPacketHandled(true);
    }
}
