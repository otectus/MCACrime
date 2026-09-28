package dev.otectus.mcacrime.network;

import dev.otectus.mcacrime.McaCrime;
import dev.otectus.mcacrime.restraint.PhysicalRestraintView;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import java.util.List;

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
 *
 * <p>The list is written truncated at {@link PacketBounds#MAX_PHYSICAL_SUBJECTS} rather than refused
 * on the write side, so an oversized prison roster still produces a decodable packet; the read side
 * rejects a count above the ceiling outright.
 */
public record PhysicalStateS2CPacket(List<PhysicalRestraintView> subjects) implements CustomPacketPayload {

    public static final Type<PhysicalStateS2CPacket> TYPE = new Type<>(McaCrime.id("physical_state"));

    public static final StreamCodec<RegistryFriendlyByteBuf, PhysicalStateS2CPacket> STREAM_CODEC =
            StreamCodec.of((buf, packet) -> {
                int count = Math.min(PacketBounds.MAX_PHYSICAL_SUBJECTS, packet.subjects.size());
                buf.writeVarInt(count);
                for (int i = 0; i < count; i++) {
                    PhysicalStateCodec.write(buf, packet.subjects.get(i));
                }
            }, buf -> new PhysicalStateS2CPacket(PacketBounds.readBoundedList(buf,
                    PacketBounds.MAX_PHYSICAL_SUBJECTS, PhysicalStateCodec::read)));

    public PhysicalStateS2CPacket {
        subjects = subjects == null ? List.of() : List.copyOf(subjects);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
