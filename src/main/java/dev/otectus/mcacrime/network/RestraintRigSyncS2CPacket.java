package dev.otectus.mcacrime.network;

import dev.otectus.mcacrime.McaCrime;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import java.util.UUID;

/**
 * Server to client: the body a restrained subject is actually being drawn on.
 *
 * <h2>Why the client cannot work this out for itself</h2>
 *
 * <p>MCA: Crime's cuffs are two bands parented to a {@code HumanoidModel}'s arms, and a settlement mod
 * can give a life stage its own rig — an egg, a grub, something with no arms at all — at which point
 * the bands hang in the air beside a body that has none. {@code ClientRestraintRig} asks the Townstead
 * bridge about it, and on a multiplayer client that bridge is never bound: it binds at
 * {@code ServerStartedEvent}, which does not happen there. So every answer on a remote client was
 * "humanoid", correct for almost everybody and wrong for exactly the villagers the check was written
 * for.
 *
 * <p>This is the missing half. The server, which has the bridge, resolves the rig and says so — for
 * restrained subjects only, when a restraint is applied and when a client starts tracking one. Not
 * broadcast for every villager: a rig is only interesting here because something is being drawn on it.
 *
 * <p>Display only, like every other sync on this channel. Nothing the client does with this can make
 * anybody restrained or unrestrained.
 *
 * @param subject  who is being drawn
 * @param humanoid whether the cuffs fit; the client's fallback is a tether band when they do not
 * @param rig      the rig id, for diagnostics and for a future layer that wants to be cleverer; may be
 *                 empty, which means the stage overrode nothing
 */
public record RestraintRigSyncS2CPacket(UUID subject, boolean humanoid,
                                        String rig) implements CustomPacketPayload {

    /** The rig id is a resource location or a geometry path; this is a generous ceiling on both. */
    public static final int MAX_RIG_LENGTH = PacketBounds.MAX_ID_LENGTH;

    public static final Type<RestraintRigSyncS2CPacket> TYPE =
            new Type<>(McaCrime.id("restraint_rig_sync"));

    public static final StreamCodec<RegistryFriendlyByteBuf, RestraintRigSyncS2CPacket> STREAM_CODEC =
            StreamCodec.composite(
                    UUIDUtil.STREAM_CODEC, RestraintRigSyncS2CPacket::subject,
                    ByteBufCodecs.BOOL, RestraintRigSyncS2CPacket::humanoid,
                    ByteBufCodecs.stringUtf8(MAX_RIG_LENGTH), RestraintRigSyncS2CPacket::rig,
                    RestraintRigSyncS2CPacket::new);

    public RestraintRigSyncS2CPacket {
        rig = rig == null ? "" : rig;
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
