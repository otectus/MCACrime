package dev.otectus.mcacrime.network;

import dev.otectus.mcacrime.McaCrime;
import dev.otectus.mcacrime.enforcement.ChallengeResponse;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import java.util.UUID;

/**
 * A player answering a guard challenge.
 *
 * <p>One of only five payloads the client is allowed to send, and like the others it carries an
 * <em>intent plus an identity</em>, never a result. The encounter id is what makes it answerable: the
 * server matches it against the challenge it actually opened, so a replayed or forged payload answers
 * an encounter that no longer exists and does nothing at all.
 *
 * <p>The response ordinal is range-checked and refused rather than clamped (spec §9.6). Under the
 * Forge channel a decode throw dropped the whole connection, so this clamped to {@code REFUSE}; a
 * payload decode failure is scoped to the payload, and an ordinal no build ever wrote is not a
 * response worth guessing at.
 */
public record GuardChallengeResponseC2SPacket(UUID encounterId,
                                              ChallengeResponse response) implements CustomPacketPayload {

    public static final Type<GuardChallengeResponseC2SPacket> TYPE =
            new Type<>(McaCrime.id("guard_challenge_response"));

    public static final StreamCodec<RegistryFriendlyByteBuf, GuardChallengeResponseC2SPacket> STREAM_CODEC =
            StreamCodec.composite(
                    UUIDUtil.STREAM_CODEC, GuardChallengeResponseC2SPacket::encounterId,
                    CrimeStreamCodecs.enumCodec(ChallengeResponse.class, "challenge response"),
                    GuardChallengeResponseC2SPacket::response,
                    GuardChallengeResponseC2SPacket::new);

    public GuardChallengeResponseC2SPacket {
        encounterId = encounterId == null ? new UUID(0L, 0L) : encounterId;
        response = response == null ? ChallengeResponse.REFUSE : response;
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
