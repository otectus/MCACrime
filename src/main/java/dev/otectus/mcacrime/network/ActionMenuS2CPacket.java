package dev.otectus.mcacrime.network;

import dev.otectus.mcacrime.client.CrimeClientHandlers;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.UUID;
import java.util.List;
import java.util.ArrayList;
import java.util.function.Supplier;

public record ActionMenuS2CPacket(UUID menuId, int revision, UUID targetId, List<ActionMenuEntry> actions) {
    public ActionMenuS2CPacket { actions = actions == null ? List.of() : List.copyOf(actions); }
    public static void encode(ActionMenuS2CPacket msg, FriendlyByteBuf buf) {
        buf.writeUUID(msg.menuId); buf.writeVarInt(msg.revision); buf.writeUUID(msg.targetId);
        buf.writeVarInt(Math.min(16, msg.actions.size()));
        for (ActionMenuEntry action : msg.actions.stream().limit(16).toList()) {
            buf.writeResourceLocation(action.actionId());
            buf.writeUtf(action.labelKey(), 128);
            buf.writeBoolean(action.available());
            buf.writeUtf(action.reasonKey(), 256);
        }
    }
    public static ActionMenuS2CPacket decode(FriendlyByteBuf buf) {
        UUID menu = buf.readUUID(); int revision = buf.readVarInt(); UUID target = buf.readUUID();
        int count = Math.min(16, Math.max(0, buf.readVarInt()));
        List<ActionMenuEntry> actions = new ArrayList<>(count);
        for (int i = 0; i < count; i++) actions.add(new ActionMenuEntry(buf.readResourceLocation(),
                buf.readUtf(128), buf.readBoolean(), buf.readUtf(256)));
        return new ActionMenuS2CPacket(menu, revision, target, actions);
    }
    public static void handle(ActionMenuS2CPacket msg, Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context context = ctx.get();
        context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> CrimeClientHandlers.onActionMenu(msg)));
        context.setPacketHandled(true);
    }
}
