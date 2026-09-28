package dev.otectus.mcacrime.network;

import dev.otectus.mcacrime.McaCrime;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import java.util.UUID;

/**
 * Server to client: forget this subject's physical state entirely.
 *
 * <p>An explicit message rather than an empty delta, because "free" and "no longer tracked" are
 * different facts and a client that conflates them leaves cuffs drawn on somebody who was released
 * out of view. It carries the revision it was issued at so a removal that overtakes a later
 * application is discarded rather than obeyed.
 */
public record PhysicalStateRemoveS2CPacket(UUID subject, long revision) implements CustomPacketPayload {

    public static final Type<PhysicalStateRemoveS2CPacket> TYPE =
            new Type<>(McaCrime.id("physical_state_remove"));

    public static final StreamCodec<RegistryFriendlyByteBuf, PhysicalStateRemoveS2CPacket> STREAM_CODEC =
            StreamCodec.composite(
                    UUIDUtil.STREAM_CODEC, PhysicalStateRemoveS2CPacket::subject,
                    CrimeStreamCodecs.LONG, PhysicalStateRemoveS2CPacket::revision,
                    PhysicalStateRemoveS2CPacket::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
