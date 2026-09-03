package dev.otectus.mcacrime.network;

import dev.otectus.mcacrime.McaCrime;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import java.util.UUID;

/** "Open the action menu about this villager." Validated in full by {@code CrimeActionService}. */
public record RequestActionMenuC2SPacket(UUID targetId) implements CustomPacketPayload {

    public static final Type<RequestActionMenuC2SPacket> TYPE =
            new Type<>(McaCrime.id("request_action_menu"));

    public static final StreamCodec<RegistryFriendlyByteBuf, RequestActionMenuC2SPacket> STREAM_CODEC =
            StreamCodec.composite(
                    UUIDUtil.STREAM_CODEC, RequestActionMenuC2SPacket::targetId,
                    RequestActionMenuC2SPacket::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
