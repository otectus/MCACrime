package dev.otectus.mcacrime.network;

import dev.otectus.mcacrime.McaCrime;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import java.util.UUID;

/** Acknowledges the first visible frame, never a response or a renewed deadline. */
public record GuardChallengeDisplayedC2SPacket(UUID encounterId) implements CustomPacketPayload {
    public static final Type<GuardChallengeDisplayedC2SPacket> TYPE =
            new Type<>(McaCrime.id("guard_challenge_displayed"));
    public static final StreamCodec<RegistryFriendlyByteBuf, GuardChallengeDisplayedC2SPacket> STREAM_CODEC =
            StreamCodec.composite(UUIDUtil.STREAM_CODEC, GuardChallengeDisplayedC2SPacket::encounterId,
                    GuardChallengeDisplayedC2SPacket::new);

    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
