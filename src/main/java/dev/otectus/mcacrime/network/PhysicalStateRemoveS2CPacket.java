package dev.otectus.mcacrime.network;

import dev.otectus.mcacrime.client.CrimeClientHandlers;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.UUID;
import java.util.function.Supplier;

/**
 * Server to client: forget this subject's physical state entirely.
 *
 * <p>An explicit message rather than an empty delta, because "free" and "no longer tracked" are
 * different facts and a client that conflates them leaves cuffs drawn on somebody who was released
 * out of view. It carries the revision it was issued at so a removal that overtakes a later
 * application is discarded rather than obeyed.
 */
public record PhysicalStateRemoveS2CPacket(UUID subject, long revision) {

    public static void encode(PhysicalStateRemoveS2CPacket msg, FriendlyByteBuf buf) {
        buf.writeUUID(msg.subject);
        buf.writeLong(msg.revision);
    }

    public static PhysicalStateRemoveS2CPacket decode(FriendlyByteBuf buf) {
        return new PhysicalStateRemoveS2CPacket(buf.readUUID(), buf.readLong());
    }

    public static void handle(PhysicalStateRemoveS2CPacket msg, Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context context = ctx.get();
        context.setPacketHandled(true);
        if (!context.getDirection().getReceptionSide().isClient()) {
            return;
        }
        context.enqueueWork(() ->
                DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> CrimeClientHandlers.onPhysicalStateRemoved(msg)));
    }
}
