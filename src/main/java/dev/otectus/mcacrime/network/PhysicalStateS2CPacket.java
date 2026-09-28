package dev.otectus.mcacrime.network;

import dev.otectus.mcacrime.client.CrimeClientHandlers;
import dev.otectus.mcacrime.restraint.PhysicalRestraintView;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.List;
import java.util.function.Supplier;

/**
 * Server to client: the full physical-restraint snapshot for a set of subjects (0.7.5 §1.6).
 *
 * <p>Sent on tracking start, reconnect and respawn — the three moments a client's picture of who is
 * restrained may be wrong and no delta will ever tell it so. {@link PhysicalStateDeltaS2CPacket}
 * carries every change after that, and {@link PhysicalStateRemoveS2CPacket} is the explicit
 * "forget this subject" the absence of a delta cannot express.
 *
 * <p>Render data only: slot, definition id, durability fraction, who holds the lead, whether a
 * device has them. No applier, no custody id, no item snapshot, no lock.
 */
public record PhysicalStateS2CPacket(List<PhysicalRestraintView> subjects) {

    public PhysicalStateS2CPacket {
        subjects = subjects == null ? List.of() : List.copyOf(subjects);
    }

    public static void encode(PhysicalStateS2CPacket msg, FriendlyByteBuf buf) {
        int count = Math.min(PacketBounds.MAX_PHYSICAL_SUBJECTS, msg.subjects.size());
        buf.writeVarInt(count);
        for (int i = 0; i < count; i++) {
            PhysicalStateCodec.write(buf, msg.subjects.get(i));
        }
    }

    public static PhysicalStateS2CPacket decode(FriendlyByteBuf buf) {
        return new PhysicalStateS2CPacket(PacketBounds.readBoundedList(buf,
                PacketBounds.MAX_PHYSICAL_SUBJECTS, PhysicalStateCodec::read));
    }

    public static void handle(PhysicalStateS2CPacket msg, Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context context = ctx.get();
        context.setPacketHandled(true);
        if (!context.getDirection().getReceptionSide().isClient()) {
            return;
        }
        context.enqueueWork(() ->
                DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> CrimeClientHandlers.onPhysicalState(msg)));
    }
}
