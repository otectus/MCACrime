package dev.otectus.mcacrime.network;

import dev.otectus.mcacrime.action.CrimeActionService;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.UUID;
import java.util.function.Supplier;

public record RequestActionMenuC2SPacket(UUID targetId) {
    public static void encode(RequestActionMenuC2SPacket msg, FriendlyByteBuf buf) { buf.writeUUID(msg.targetId); }
    public static RequestActionMenuC2SPacket decode(FriendlyByteBuf buf) { return new RequestActionMenuC2SPacket(buf.readUUID()); }
    public static void handle(RequestActionMenuC2SPacket msg, Supplier<NetworkEvent.Context> ctx) {
        ServerPacketGuard.accept(ctx, RequestBudget.Category.MENU,
                sender -> CrimeActionService.openMenu(sender, msg.targetId()));
    }
}
