package dev.otectus.mcacrime;

import dev.otectus.mcacrime.enforcement.ChallengeResponse;
import dev.otectus.mcacrime.network.GuardChallengeResponseC2SPacket;
import dev.otectus.mcacrime.network.GuardChallengeS2CPacket;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.DecoderException;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

/** Revisions travel with both sides of an offer; malformed offers never become a refusal. */
class PacketBoundsTest {
    private static RegistryFriendlyByteBuf buffer() {
        return new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
    }
    private static <T> void roundTrip(StreamCodec<RegistryFriendlyByteBuf, T> codec, T sent) {
        var buf = buffer();
        try {
            codec.encode(buf, sent);
            assertEquals(sent, codec.decode(buf));
            assertEquals(0, buf.readableBytes());
        } finally { buf.release(); }
    }
    @Test void everyChallengeResponseRoundTripsWithItsRevision() {
        for (var response : ChallengeResponse.values())
            roundTrip(GuardChallengeResponseC2SPacket.STREAM_CODEC,
                    new GuardChallengeResponseC2SPacket(UUID.randomUUID(), response, 37L));
    }
    @Test void negativeResponseRevisionIsRejected() {
        var buf = buffer();
        try {
            GuardChallengeResponseC2SPacket.STREAM_CODEC.encode(buf,
                    new GuardChallengeResponseC2SPacket(UUID.randomUUID(), ChallengeResponse.PAY_FINE, -1L));
            assertThrows(DecoderException.class, () -> GuardChallengeResponseC2SPacket.STREAM_CODEC.decode(buf));
        } finally { buf.release(); }
    }
    @Test void anUnknownResponseIsRejectedRatherThanDecodedAsRefuse() {
        var buf = buffer();
        try {
            buf.writeUUID(UUID.randomUUID()); buf.writeVarInt(999); buf.writeVarLong(1);
            assertThrows(DecoderException.class, () -> GuardChallengeResponseC2SPacket.STREAM_CODEC.decode(buf));
        } finally { buf.release(); }
    }
    @Test void challengeOfferRevisionRoundTrips() {
        roundTrip(GuardChallengeS2CPacket.STREAM_CODEC, offer(37L));
        roundTrip(GuardChallengeS2CPacket.STREAM_CODEC, GuardChallengeS2CPacket.closed());
    }
    @Test void negativeOfferRevisionIsRejected() {
        var buf = buffer();
        try {
            GuardChallengeS2CPacket.STREAM_CODEC.encode(buf, offer(-1L));
            assertThrows(DecoderException.class, () -> GuardChallengeS2CPacket.STREAM_CODEC.decode(buf));
        } finally { buf.release(); }
    }
    private static GuardChallengeS2CPacket offer(long revision) {
        return new GuardChallengeS2CPacket(true, UUID.randomUUID(), Component.literal("Guard"),
                Component.literal("Village"), 2, 45L, true, 300L, revision);
    }
}
