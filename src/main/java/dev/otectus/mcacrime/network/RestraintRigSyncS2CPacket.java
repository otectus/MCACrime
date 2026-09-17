package dev.otectus.mcacrime.network;

import dev.otectus.mcacrime.client.CrimeClientHandlers;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.UUID;
import java.util.function.Supplier;

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
public record RestraintRigSyncS2CPacket(UUID subject, boolean humanoid, String rig) {

    /** The rig id is a resource location or a geometry path; this is a generous ceiling on both. */
    public static final int MAX_RIG_LENGTH = PacketBounds.MAX_ID_LENGTH;

    public RestraintRigSyncS2CPacket {
        rig = rig == null ? "" : rig;
    }

    public static void encode(RestraintRigSyncS2CPacket msg, FriendlyByteBuf buf) {
        buf.writeUUID(msg.subject);
        buf.writeBoolean(msg.humanoid);
        buf.writeUtf(msg.rig, MAX_RIG_LENGTH);
    }

    public static RestraintRigSyncS2CPacket decode(FriendlyByteBuf buf) {
        return new RestraintRigSyncS2CPacket(buf.readUUID(), buf.readBoolean(),
                buf.readUtf(MAX_RIG_LENGTH));
    }

    public static void handle(RestraintRigSyncS2CPacket msg, Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context context = ctx.get();
        context.setPacketHandled(true);
        if (!context.getDirection().getReceptionSide().isClient()) {
            return;
        }
        context.enqueueWork(() ->
                DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> CrimeClientHandlers.onRestraintRig(msg)));
    }
}
