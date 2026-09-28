package dev.otectus.mcacrime;

import dev.otectus.mcacrime.enforcement.ChallengeResponse;
import dev.otectus.mcacrime.network.GuardChallengeResponseC2SPacket;
import dev.otectus.mcacrime.network.GuardChallengeS2CPacket;
import dev.otectus.mcacrime.network.PacketBounds;
import dev.otectus.mcacrime.network.PhysicalStateDeltaS2CPacket;
import dev.otectus.mcacrime.network.PhysicalStateS2CPacket;
import dev.otectus.mcacrime.network.RestraintStruggleC2SPacket;
import dev.otectus.mcacrime.network.SelfRestraintC2SPacket;
import dev.otectus.mcacrime.restraint.RestraintSlot;
import dev.otectus.mcacrime.restraint.PhysicalRestraintView;
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
    // ------------------------------------------------------------------ 0.7.5 physical state

    @Test void aFourthRestraintSlotIsRejected() {
        var buf = buffer();
        try {
            buf.writeVarInt(PacketBounds.MAX_RESTRAINT_SLOTS + 1);
            assertThrows(DecoderException.class,
                    () -> PacketBounds.readCount(buf, PacketBounds.MAX_RESTRAINT_SLOTS),
                    "there are three body slots; a peer claiming four is not describing a subject this "
                            + "build can have");
            assertEquals(3, PacketBounds.MAX_RESTRAINT_SLOTS);
        } finally { buf.release(); }
    }

    @Test void anOverLimitPhysicalSnapshotIsRejected() {
        var buf = buffer();
        try {
            buf.writeVarInt(PacketBounds.MAX_PHYSICAL_SUBJECTS + 1);
            assertThrows(DecoderException.class, () -> PhysicalStateS2CPacket.STREAM_CODEC.decode(buf));
        } finally { buf.release(); }
    }

    @Test void anOversizedSnapshotIsTruncatedOnTheWriteSideRatherThanMadeUndecodable() {
        var views = new java.util.ArrayList<PhysicalRestraintView>();
        for (int i = 0; i < PacketBounds.MAX_PHYSICAL_SUBJECTS + 10; i++)
            views.add(PhysicalRestraintView.empty(UUID.randomUUID(), 1L, 1L));
        var buf = buffer();
        try {
            PhysicalStateS2CPacket.STREAM_CODEC.encode(buf, new PhysicalStateS2CPacket(views));
            assertEquals(PacketBounds.MAX_PHYSICAL_SUBJECTS,
                    PhysicalStateS2CPacket.STREAM_CODEC.decode(buf).subjects().size());
            assertEquals(0, buf.readableBytes());
        } finally { buf.release(); }
    }

    @Test void aDurabilityFractionOutsideZeroToOneIsRejected() {
        for (float bad : new float[]{-0.01F, 1.01F, Float.NaN, Float.POSITIVE_INFINITY}) {
            var buf = buffer();
            try {
                buf.writeFloat(bad);
                assertThrows(DecoderException.class, () -> PacketBounds.readUnitFraction(buf),
                        bad + " was accepted as a durability fraction");
            } finally { buf.release(); }
        }
        var good = buffer();
        try {
            good.writeFloat(0.25F);
            assertEquals(0.25F, PacketBounds.readUnitFraction(good));
        } finally { good.release(); }
    }

    @Test void anUnknownSlotNameIsRejectedRatherThanSubstituted() {
        var buf = buffer();
        try {
            buf.writeUUID(UUID.randomUUID());
            buf.writeLong(1L);
            buf.writeLong(1L);
            buf.writeVarInt(-1);
            buf.writeBoolean(false);
            buf.writeVarInt(1);
            buf.writeUtf("TAIL", PacketBounds.MAX_ID_LENGTH);
            assertThrows(DecoderException.class, () -> PhysicalStateDeltaS2CPacket.STREAM_CODEC.decode(buf),
                    "a substituted slot would draw somebody's cuffs on a limb the server never named");
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
    // ------------------------------------------------------------------ self application (R10)

    /**
     * The self-application request carries two bounded values and no identity at all.
     *
     * <p>Deliberately so: the subject is the connection's player, so there is no field here that could
     * name somebody else, and the worst a forged payload can ask for is a slot the sender's own rig or
     * held item does not support — which the server refuses.
     */
    @Test void aSelfRestraintRequestRoundTripsEverySlotAndEitherHand() {
        for (RestraintSlot slot : RestraintSlot.values()) {
            for (boolean offHand : new boolean[]{false, true}) {
                roundTrip(SelfRestraintC2SPacket.STREAM_CODEC, new SelfRestraintC2SPacket(slot, offHand));
            }
        }
    }

    /**
     * An out-of-range slot ordinal is refused rather than read as the arms.
     *
     * <p>The one deliberate divergence from the Forge baseline, which falls back to
     * {@code RestraintSlot.ARMS} because a decode throw there drops the whole connection. Under the
     * payload API the failure is scoped to the payload, so this line refuses — the same policy
     * {@code CrimeStreamCodecs.enumCodec} applies to every other enum on this channel (spec §9.6).
     */
    @Test void anUnknownSelfRestraintSlotIsRejected() {
        var buf = buffer();
        try {
            buf.writeVarInt(97);
            buf.writeBoolean(false);
            assertThrows(DecoderException.class, () -> SelfRestraintC2SPacket.STREAM_CODEC.decode(buf));
        } finally { buf.release(); }
    }

    /** A struggle input is four bounded numbers, and nothing in it names an actor or an outcome. */
    @Test void aStruggleInputRoundTripsEverySlot() {
        for (RestraintSlot slot : RestraintSlot.values()) {
            roundTrip(RestraintStruggleC2SPacket.STREAM_CODEC,
                    new RestraintStruggleC2SPacket(42L, slot, 2, 7));
        }
    }

    @Test void anUnknownStruggleSlotIsRejected() {
        var buf = buffer();
        try {
            buf.writeLong(1L);
            buf.writeVarInt(97);
            buf.writeVarInt(0);
            buf.writeVarInt(0);
            assertThrows(DecoderException.class,
                    () -> RestraintStruggleC2SPacket.STREAM_CODEC.decode(buf));
        } finally { buf.release(); }
    }

    private static GuardChallengeS2CPacket offer(long revision) {
        return new GuardChallengeS2CPacket(true, UUID.randomUUID(), Component.literal("Guard"),
                Component.literal("Village"), 2, 45L, true, 300L, revision);
    }
}
