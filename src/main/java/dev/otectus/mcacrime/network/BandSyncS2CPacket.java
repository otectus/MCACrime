package dev.otectus.mcacrime.network;

import dev.otectus.mcacrime.client.CrimeClientHandlers;
import dev.otectus.mcacrime.crime.Band;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.UUID;
import java.util.function.Supplier;

/** Server→client: a single player's band changed (for non-destructive nameplate coloring). */
public record BandSyncS2CPacket(UUID player, Band band) {

    public static void encode(BandSyncS2CPacket msg, FriendlyByteBuf buf) {
        buf.writeUUID(msg.player);
        buf.writeEnum(msg.band);
    }

    public static BandSyncS2CPacket decode(FriendlyByteBuf buf) {
        return new BandSyncS2CPacket(buf.readUUID(), buf.readEnum(Band.class));
    }

    public static void handle(BandSyncS2CPacket msg, Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context context = ctx.get();
        context.enqueueWork(() ->
                DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> CrimeClientHandlers.onBandSync(msg)));
        context.setPacketHandled(true);
    }
}
