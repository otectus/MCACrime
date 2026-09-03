package dev.otectus.mcacrime.network;

import dev.otectus.mcacrime.client.CrimeClientHandlers;
import dev.otectus.mcacrime.crime.Band;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

/** Server→client: a full snapshot of every online player's band, sent to a joining client. */
public record BandBulkSyncS2CPacket(Map<UUID, Band> bands) {

    public static void encode(BandBulkSyncS2CPacket msg, FriendlyByteBuf buf) {
        buf.writeMap(msg.bands, FriendlyByteBuf::writeUUID, FriendlyByteBuf::writeEnum);
    }

    public static BandBulkSyncS2CPacket decode(FriendlyByteBuf buf) {
        Map<UUID, Band> bands = buf.readMap(HashMap::new, FriendlyByteBuf::readUUID, b -> b.readEnum(Band.class));
        return new BandBulkSyncS2CPacket(bands);
    }

    public static void handle(BandBulkSyncS2CPacket msg, Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context context = ctx.get();
        context.enqueueWork(() ->
                DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> CrimeClientHandlers.onBandBulk(msg)));
        context.setPacketHandled(true);
    }
}
