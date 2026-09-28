package dev.otectus.mcacrime.network;

import dev.otectus.mcacrime.McaCrime;
import dev.otectus.mcacrime.restraint.PhysicalRestraintView;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Server to client: one subject's physical state changed.
 *
 * <p>The whole subject rather than the one slot that moved. A per-slot delta would need the client to
 * hold a correct base state for the arithmetic to mean anything, and the one thing a client's copy is
 * not guaranteed to be is correct; sending the subject's three slots costs a few bytes and cannot
 * drift. The view's revision is what makes an out-of-order arrival discardable.
 *
 * <p>The component is {@code subject} rather than {@code state}: {@code CustomPacketPayload} already
 * declares {@code type()} for the payload's own id, so no component here may be called {@code type}.
 */
public record PhysicalStateDeltaS2CPacket(PhysicalRestraintView subject) implements CustomPacketPayload {

    public static final Type<PhysicalStateDeltaS2CPacket> TYPE =
            new Type<>(McaCrime.id("physical_state_delta"));

    public static final StreamCodec<RegistryFriendlyByteBuf, PhysicalStateDeltaS2CPacket> STREAM_CODEC =
            StreamCodec.composite(
                    PhysicalStateCodec.VIEW, PhysicalStateDeltaS2CPacket::subject,
                    PhysicalStateDeltaS2CPacket::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
