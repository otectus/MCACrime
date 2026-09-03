package dev.otectus.mcacrime.network;

import dev.otectus.mcacrime.McaCrime;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.UUID;

/** "Run this row of the menu you just sent me." Every field is rechecked server-side before anything runs. */
public record StartActionC2SPacket(UUID nonce, UUID menuId, int menuRevision,
                                   ResourceLocation actionId, UUID targetId) implements CustomPacketPayload {

    public static final Type<StartActionC2SPacket> TYPE = new Type<>(McaCrime.id("start_action"));

    public static final StreamCodec<RegistryFriendlyByteBuf, StartActionC2SPacket> STREAM_CODEC =
            StreamCodec.composite(
                    UUIDUtil.STREAM_CODEC, StartActionC2SPacket::nonce,
                    UUIDUtil.STREAM_CODEC, StartActionC2SPacket::menuId,
                    ByteBufCodecs.VAR_INT, StartActionC2SPacket::menuRevision,
                    ResourceLocation.STREAM_CODEC, StartActionC2SPacket::actionId,
                    UUIDUtil.STREAM_CODEC, StartActionC2SPacket::targetId,
                    StartActionC2SPacket::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
