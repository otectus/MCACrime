package dev.otectus.mcacrime.network;

import dev.otectus.mcacrime.McaCrime;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import java.util.UUID;

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
public record RestraintSyncS2CPacket(UUID subject, boolean restrained,
                                     int guardEntityId) implements CustomPacketPayload {

    public static final Type<RestraintSyncS2CPacket> TYPE = new Type<>(McaCrime.id("restraint_sync"));

    public static final StreamCodec<RegistryFriendlyByteBuf, RestraintSyncS2CPacket> STREAM_CODEC =
            StreamCodec.composite(
                    UUIDUtil.STREAM_CODEC, RestraintSyncS2CPacket::subject,
                    ByteBufCodecs.BOOL, RestraintSyncS2CPacket::restrained,
                    ByteBufCodecs.VAR_INT, RestraintSyncS2CPacket::guardEntityId,
                    RestraintSyncS2CPacket::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
