package dev.otectus.mcacrime.network;

import dev.otectus.mcacrime.client.CrimeClientHandlers;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Server to client: every restrained player right now, sent to a joining client.
 *
 * <p>The twin of {@code BandBulkSyncS2CPacket}, and for the same reason: a client that connects
 * halfway through somebody else's arrest would otherwise see an uncuffed player walking beside a guard
 * until the next state change happened to broadcast.
 */
public record RestraintBulkSyncS2CPacket(Map<UUID, Integer> restrained) {

    public static void encode(RestraintBulkSyncS2CPacket msg, FriendlyByteBuf buf) {
        buf.writeMap(msg.restrained, FriendlyByteBuf::writeUUID, FriendlyByteBuf::writeVarInt);
    }

    public static RestraintBulkSyncS2CPacket decode(FriendlyByteBuf buf) {
        Map<UUID, Integer> map = buf.readMap(HashMap::new, FriendlyByteBuf::readUUID,
                FriendlyByteBuf::readVarInt);
        return new RestraintBulkSyncS2CPacket(map);
    }

    public static void handle(RestraintBulkSyncS2CPacket msg, Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context context = ctx.get();
        context.enqueueWork(() ->
                DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> CrimeClientHandlers.onRestraintBulk(msg)));
        context.setPacketHandled(true);
    }
}
