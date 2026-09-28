package dev.otectus.mcacrime.network;

import dev.otectus.mcacrime.client.CrimeClientHandlers;
import dev.otectus.mcacrime.restraint.PhysicalRestraintView;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * Server to client: one subject's physical state changed.
 *
 * <p>The whole subject rather than the one slot that moved. A per-slot delta would need the client to
 * hold a correct base state for the arithmetic to mean anything, and the one thing a client's copy is
 * not guaranteed to be is correct; sending the subject's three slots costs a few bytes and cannot
 * drift. The view's revision is what makes an out-of-order arrival discardable.
 */
public record PhysicalStateDeltaS2CPacket(PhysicalRestraintView subject) {

    public static void encode(PhysicalStateDeltaS2CPacket msg, FriendlyByteBuf buf) {
        PhysicalStateCodec.write(buf, msg.subject);
    }

    public static PhysicalStateDeltaS2CPacket decode(FriendlyByteBuf buf) {
        return new PhysicalStateDeltaS2CPacket(PhysicalStateCodec.read(buf));
    }

    public static void handle(PhysicalStateDeltaS2CPacket msg, Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context context = ctx.get();
        context.setPacketHandled(true);
        if (!context.getDirection().getReceptionSide().isClient()) {
            return;
        }
        context.enqueueWork(() ->
                DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> CrimeClientHandlers.onPhysicalStateDelta(msg)));
    }
}
