package dev.otectus.mcacrime.network;

import dev.otectus.mcacrime.McaCrime;
import dev.otectus.mcacrime.enforcement.RestraintVisualState;
import dev.otectus.mcacrime.enforcement.RestraintVisualType;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import java.util.UUID;

/**
 * Server to client: one subject is, or is no longer, restrained, with what, and who is holding them.
 *
 * <p>Broadcast rather than sent to the subject alone, because the binding and the lead are drawn by
 * everybody who can see the arrest, not only by the person in them. The subject may be a player or a
 * villager, which is why it is a UUID and not an entity id: a captive can be in an unloaded chunk
 * when the packet goes out.
 *
 * <p>{@code guardEntityId} is an entity id rather than a UUID, and that is a rendering decision: the
 * rope is drawn every frame, and a client resolves an entity id in constant time while a UUID lookup
 * costs a scan. Ids are per level, so a prisoner and a guard that do not both resolve in the viewer's
 * own level simply draw nothing, which is also the correct answer across a dimension boundary.
 * {@code -1} means nobody is holding them.
 *
 * <p>The visual field is {@code visual} rather than {@code type}: {@code CustomPacketPayload} already
 * declares {@code type()} for the payload's own id, and a record component of that name would be an
 * accessor clash. The state record it is built from still calls it {@code type}.
 *
 * <p>Display only. Nothing the client does with this can change whether the subject is actually
 * restrained; that lives in {@code ArrestPhase} and the custody table on the server.
 */
public record RestraintSyncS2CPacket(UUID subject, boolean restrained, RestraintVisualType visual,
                                     int guardEntityId) implements CustomPacketPayload {

    public static final Type<RestraintSyncS2CPacket> TYPE = new Type<>(McaCrime.id("restraint_sync"));

    public static final StreamCodec<RegistryFriendlyByteBuf, RestraintSyncS2CPacket> STREAM_CODEC =
            StreamCodec.composite(
                    UUIDUtil.STREAM_CODEC, RestraintSyncS2CPacket::subject,
                    ByteBufCodecs.BOOL, RestraintSyncS2CPacket::restrained,
                    CrimeStreamCodecs.enumCodec(RestraintVisualType.class, "restraint visual type"),
                    RestraintSyncS2CPacket::visual,
                    ByteBufCodecs.VAR_INT, RestraintSyncS2CPacket::guardEntityId,
                    RestraintSyncS2CPacket::new);

    /** Builds the packet from the resolver's answer, so no caller re-derives the three fields. */
    public static RestraintSyncS2CPacket of(UUID subject, RestraintVisualState state) {
        return new RestraintSyncS2CPacket(subject, state.restrained(), state.type(),
                state.escortEntityId());
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
