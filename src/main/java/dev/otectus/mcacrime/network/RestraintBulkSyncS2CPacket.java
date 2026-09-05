package dev.otectus.mcacrime.network;

import dev.otectus.mcacrime.client.CrimeClientHandlers;
import dev.otectus.mcacrime.enforcement.RestraintVisualState;
import dev.otectus.mcacrime.enforcement.RestraintVisualType;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Server to client: every restrained subject right now, sent to a joining client.
 *
 * <p>The twin of {@code BandBulkSyncS2CPacket}, and for the same reason: a client that connects
 * halfway through somebody else's arrest would otherwise see an uncuffed player walking beside a guard
 * until the next state change happened to broadcast. Restrained villagers are in here too — they can
 * sit in a cell for a very long time without a single state change to ride along on.
 */
public record RestraintBulkSyncS2CPacket(Map<UUID, RestraintVisualState> restrained) {

    public static void encode(RestraintBulkSyncS2CPacket msg, FriendlyByteBuf buf) {
        buf.writeMap(msg.restrained, FriendlyByteBuf::writeUUID, RestraintBulkSyncS2CPacket::writeState);
    }

    public static RestraintBulkSyncS2CPacket decode(FriendlyByteBuf buf) {
        Map<UUID, RestraintVisualState> map = buf.readMap(HashMap::new, FriendlyByteBuf::readUUID,
                RestraintBulkSyncS2CPacket::readState);
        return new RestraintBulkSyncS2CPacket(map);
    }

    private static void writeState(FriendlyByteBuf buf, RestraintVisualState state) {
        buf.writeBoolean(state.restrained());
        buf.writeEnum(state.type());
        buf.writeVarInt(state.escortEntityId());
    }

    private static RestraintVisualState readState(FriendlyByteBuf buf) {
        return new RestraintVisualState(buf.readBoolean(), buf.readEnum(RestraintVisualType.class),
                buf.readVarInt());
    }

    public static void handle(RestraintBulkSyncS2CPacket msg, Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context context = ctx.get();
        context.enqueueWork(() ->
                DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> CrimeClientHandlers.onRestraintBulk(msg)));
        context.setPacketHandled(true);
    }
}
