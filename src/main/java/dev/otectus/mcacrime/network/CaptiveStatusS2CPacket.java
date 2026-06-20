package dev.otectus.mcacrime.network;

import dev.otectus.mcacrime.client.CrimeClientHandlers;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * Server→client: the receiving player's own captivity status (spec §10.3) — whether they are held, whether
 * it is lawful (jail) or a kidnapping, their captor's name, and the remaining real-time captivity cap. Sent
 * only on custody transitions and login (not the high-frequency self-status), and display-only.
 */
public record CaptiveStatusS2CPacket(boolean captive, boolean lawful, String captor, long capRemainingTicks) {

    public static void encode(CaptiveStatusS2CPacket msg, FriendlyByteBuf buf) {
        buf.writeBoolean(msg.captive);
        buf.writeBoolean(msg.lawful);
        buf.writeUtf(msg.captor);
        buf.writeLong(msg.capRemainingTicks);
    }

    public static CaptiveStatusS2CPacket decode(FriendlyByteBuf buf) {
        return new CaptiveStatusS2CPacket(buf.readBoolean(), buf.readBoolean(), buf.readUtf(), buf.readLong());
    }

    public static void handle(CaptiveStatusS2CPacket msg, Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context context = ctx.get();
        context.enqueueWork(() ->
                DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> CrimeClientHandlers.onCaptiveStatus(msg)));
        context.setPacketHandled(true);
    }
}
