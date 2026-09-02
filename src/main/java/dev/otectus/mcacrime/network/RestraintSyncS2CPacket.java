package dev.otectus.mcacrime.network;

import dev.otectus.mcacrime.client.CrimeClientHandlers;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.UUID;
import java.util.function.Supplier;

/**
 * Server to client: one player is, or is no longer, restrained, and who is holding them.
 *
 * <p>Broadcast rather than sent to the subject alone, because the cuffs and the lead are drawn by
 * everybody who can see the arrest, not only by the person in them.
 *
 * <p>{@code guardEntityId} is an entity id rather than a UUID, and that is a rendering decision: the
 * rope is drawn every frame, and a client resolves an entity id in constant time while a UUID lookup
 * costs a scan. Ids are per level, so a prisoner and a guard that do not both resolve in the viewer's
 * own level simply draw nothing, which is also the correct answer across a dimension boundary.
 * {@code -1} means nobody is holding them.
 *
 * <p>Display only. Nothing the client does with this can change whether the player is actually
 * restrained; that lives in {@code ArrestPhase} on the server.
 */
public record RestraintSyncS2CPacket(UUID subject, boolean restrained, int guardEntityId) {

    public static void encode(RestraintSyncS2CPacket msg, FriendlyByteBuf buf) {
        buf.writeUUID(msg.subject);
        buf.writeBoolean(msg.restrained);
        buf.writeVarInt(msg.guardEntityId);
    }

    public static RestraintSyncS2CPacket decode(FriendlyByteBuf buf) {
        return new RestraintSyncS2CPacket(buf.readUUID(), buf.readBoolean(), buf.readVarInt());
    }

    public static void handle(RestraintSyncS2CPacket msg, Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context context = ctx.get();
        context.enqueueWork(() ->
                DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> CrimeClientHandlers.onRestraint(msg)));
        context.setPacketHandled(true);
    }
}
