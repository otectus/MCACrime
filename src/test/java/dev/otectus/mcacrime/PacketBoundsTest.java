package dev.otectus.mcacrime;

import dev.otectus.mcacrime.crime.Band;
import dev.otectus.mcacrime.enforcement.ChallengeResponse;
import dev.otectus.mcacrime.ledger.Resolution;
import dev.otectus.mcacrime.network.BandBulkSyncS2CPacket;
import dev.otectus.mcacrime.network.CaseLedgerS2CPacket;
import dev.otectus.mcacrime.lockpick.LockpickOutcome;
import dev.otectus.mcacrime.network.GuardChallengeResponseC2SPacket;
import dev.otectus.mcacrime.network.LockpickAttemptC2SPacket;
import dev.otectus.mcacrime.network.LockpickResultS2CPacket;
import dev.otectus.mcacrime.network.RestraintStruggleC2SPacket;
import dev.otectus.mcacrime.network.PacketBounds;
import dev.otectus.mcacrime.network.PhysicalStateDeltaS2CPacket;
import dev.otectus.mcacrime.network.PhysicalStateS2CPacket;
import dev.otectus.mcacrime.network.SelfRestraintC2SPacket;
import dev.otectus.mcacrime.restraint.RestraintSlot;
import dev.otectus.mcacrime.restraint.PhysicalRestraintView;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.DecoderException;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The decoders, against payloads no honest client sends.
 *
 * <p>The bad cases all assert {@link DecoderException} rather than a clamped result. That distinction
 * is the whole point of the change: a clamped count leaves the reader at the wrong offset, so the rest
 * of the packet decodes into a well-formed message the sender never wrote, and a clamped enum turns
 * "this packet is unreadable" into a decision attributed to the player — which is how a decode failure
 * used to become a refused guard challenge.
 */
class PacketBoundsTest {

    private static FriendlyByteBuf buffer() {
        return new FriendlyByteBuf(Unpooled.buffer());
    }

    @Test
    void anOverLimitCountIsRejected() {
        FriendlyByteBuf buf = buffer();
        buf.writeVarInt(PacketBounds.MAX_MAP_ENTRIES + 1);
        assertThrows(DecoderException.class, () -> PacketBounds.readCount(buf, PacketBounds.MAX_MAP_ENTRIES));

        FriendlyByteBuf bulk = buffer();
        bulk.writeVarInt(PacketBounds.MAX_MAP_ENTRIES + 1);
        assertThrows(DecoderException.class, () -> BandBulkSyncS2CPacket.decode(bulk));

        FriendlyByteBuf ledger = buffer();
        ledger.writeVarInt(PacketBounds.MAX_DOSSIER_ROWS + 1);
        assertThrows(DecoderException.class, () -> CaseLedgerS2CPacket.decode(ledger));
    }

    @Test
    void aNegativeCountIsRejected() {
        FriendlyByteBuf buf = buffer();
        buf.writeVarInt(-1);
        assertThrows(DecoderException.class, () -> PacketBounds.readCount(buf, PacketBounds.MAX_MENU_ENTRIES));

        FriendlyByteBuf list = buffer();
        list.writeVarInt(-4);
        assertThrows(DecoderException.class,
                () -> PacketBounds.readBoundedList(list, PacketBounds.MAX_MENU_ENTRIES, FriendlyByteBuf::readUUID));
    }

    @Test
    void anOverLongStringIsRejected() {
        FriendlyByteBuf id = buffer();
        id.writeUtf("x".repeat(PacketBounds.MAX_ID_LENGTH + 1), 512);
        assertThrows(DecoderException.class, () -> PacketBounds.readId(id));

        FriendlyByteBuf display = buffer();
        display.writeUtf("x".repeat(PacketBounds.MAX_DISPLAY_LENGTH + 1), 1024);
        assertThrows(DecoderException.class, () -> PacketBounds.readDisplay(display));

        FriendlyByteBuf malformed = buffer();
        malformed.writeUtf("Not An Id", PacketBounds.MAX_ID_LENGTH);
        assertThrows(DecoderException.class, () -> PacketBounds.readResourceLocation(malformed));
    }

    @Test
    void anUnknownEnumNameIsRejectedRatherThanDecodedAsRefuse() {
        FriendlyByteBuf buf = buffer();
        buf.writeUtf("SOMETHING_ELSE", PacketBounds.MAX_ID_LENGTH);
        assertTrue(PacketBounds.readEnum(buf, ChallengeResponse.class).isEmpty());

        FriendlyByteBuf packet = buffer();
        packet.writeUUID(UUID.randomUUID());
        packet.writeUtf("SOMETHING_ELSE", PacketBounds.MAX_ID_LENGTH);
        assertThrows(DecoderException.class, () -> GuardChallengeResponseC2SPacket.decode(packet));
    }

    @Test
    void everyChallengeResponseRoundTrips() {
        for (ChallengeResponse response : ChallengeResponse.values()) {
            GuardChallengeResponseC2SPacket sent =
                    new GuardChallengeResponseC2SPacket(UUID.randomUUID(), response, 37L);
            FriendlyByteBuf buf = buffer();
            GuardChallengeResponseC2SPacket.encode(sent, buf);
            assertEquals(sent, GuardChallengeResponseC2SPacket.decode(buf));
        }
    }

    @Test
    void negativeChallengeRevisionIsRejected() {
        FriendlyByteBuf packet = buffer();
        GuardChallengeResponseC2SPacket.encode(new GuardChallengeResponseC2SPacket(
                UUID.randomUUID(), ChallengeResponse.PAY_FINE, -1L), packet);
        assertThrows(DecoderException.class, () -> GuardChallengeResponseC2SPacket.decode(packet));
    }

    @Test
    void challengeOfferRevisionRoundTrips() {
        var sent = new dev.otectus.mcacrime.network.GuardChallengeS2CPacket(true, UUID.randomUUID(),
                net.minecraft.network.chat.Component.literal("Guard"),
                net.minecraft.network.chat.Component.literal("Village"), 2, 45L, true, 200L, 37L);
        FriendlyByteBuf packet = buffer();
        dev.otectus.mcacrime.network.GuardChallengeS2CPacket.encode(sent, packet);
        assertEquals(sent, dev.otectus.mcacrime.network.GuardChallengeS2CPacket.decode(packet));
    }

    @Test
    void aFullBandSnapshotRoundTrips() {
        Map<UUID, Band> bands = new LinkedHashMap<>();
        for (int i = 0; i < PacketBounds.MAX_MAP_ENTRIES; i++) {
            bands.put(UUID.randomUUID(), Band.values()[i % Band.values().length]);
        }
        FriendlyByteBuf buf = buffer();
        BandBulkSyncS2CPacket.encode(new BandBulkSyncS2CPacket(bands), buf);
        assertEquals(bands, BandBulkSyncS2CPacket.decode(buf).bands());
    }

    @Test
    void anOversizedSnapshotIsTruncatedOnWriteRatherThanMadeUndecodable() {
        Map<UUID, Band> bands = new LinkedHashMap<>();
        for (int i = 0; i < PacketBounds.MAX_MAP_ENTRIES + 20; i++) {
            bands.put(UUID.randomUUID(), Band.GREY);
        }
        FriendlyByteBuf buf = buffer();
        BandBulkSyncS2CPacket.encode(new BandBulkSyncS2CPacket(bands), buf);
        assertEquals(PacketBounds.MAX_MAP_ENTRIES, BandBulkSyncS2CPacket.decode(buf).bands().size());
    }

    // ------------------------------------------------------------------ 0.7.5 physical state

    @Test
    void aFourthRestraintSlotIsRejected() {
        FriendlyByteBuf buf = buffer();
        buf.writeVarInt(PacketBounds.MAX_RESTRAINT_SLOTS + 1);

        assertThrows(DecoderException.class,
                () -> PacketBounds.readCount(buf, PacketBounds.MAX_RESTRAINT_SLOTS),
                "there are three body slots; a peer claiming four is not describing a subject this "
                        + "build can have");
        assertEquals(3, PacketBounds.MAX_RESTRAINT_SLOTS);
    }

    @Test
    void anOverLimitPhysicalSnapshotIsRejected() {
        FriendlyByteBuf buf = buffer();
        buf.writeVarInt(PacketBounds.MAX_PHYSICAL_SUBJECTS + 1);

        assertThrows(DecoderException.class, () -> PhysicalStateS2CPacket.decode(buf));
    }

    @Test
    void anOversizedSnapshotIsTruncatedOnTheWriteSideRatherThanMadeUndecodable() {
        List<PhysicalRestraintView> views = new java.util.ArrayList<>();
        for (int i = 0; i < PacketBounds.MAX_PHYSICAL_SUBJECTS + 10; i++) {
            views.add(PhysicalRestraintView.empty(UUID.randomUUID(), 1L, 1L));
        }
        FriendlyByteBuf buf = buffer();
        PhysicalStateS2CPacket.encode(new PhysicalStateS2CPacket(views), buf);

        assertEquals(PacketBounds.MAX_PHYSICAL_SUBJECTS, PhysicalStateS2CPacket.decode(buf).subjects().size());
        assertEquals(0, buf.readableBytes());
    }

    @Test
    void aDurabilityFractionOutsideZeroToOneIsRejected() {
        for (float bad : new float[]{-0.01F, 1.01F, Float.NaN, Float.POSITIVE_INFINITY}) {
            FriendlyByteBuf buf = buffer();
            buf.writeFloat(bad);
            assertThrows(DecoderException.class, () -> PacketBounds.readUnitFraction(buf),
                    bad + " was accepted as a durability fraction");
        }

        FriendlyByteBuf good = buffer();
        good.writeFloat(0.25F);
        assertEquals(0.25F, PacketBounds.readUnitFraction(good));
    }

    @Test
    void anUnknownSlotNameIsRejectedRatherThanSubstituted() {
        FriendlyByteBuf buf = buffer();
        buf.writeUUID(UUID.randomUUID());
        buf.writeLong(1L);
        buf.writeLong(1L);
        buf.writeVarInt(-1);
        buf.writeBoolean(false);
        buf.writeVarInt(1);
        buf.writeUtf("TAIL", PacketBounds.MAX_ID_LENGTH);

        assertThrows(DecoderException.class, () -> PhysicalStateDeltaS2CPacket.decode(buf),
                "a substituted slot would draw somebody's cuffs on a limb the server never named");
    }

    @Test
    void aFullDossierRoundTrips() {
        List<CaseLedgerS2CPacket.Row> rows = new java.util.ArrayList<>();
        for (int i = 0; i < PacketBounds.MAX_DOSSIER_ROWS; i++) {
            rows.add(new CaseLedgerS2CPacket.Row(UUID.randomUUID(), new ResourceLocation("mcacrime", "theft"),
                    Resolution.UNRESOLVED, 100L + i, 25L, i % 2 == 0, "minecraft:overworld/0"));
        }
        CaseLedgerS2CPacket sent = new CaseLedgerS2CPacket(rows, 4, 900L);
        FriendlyByteBuf buf = buffer();
        CaseLedgerS2CPacket.encode(sent, buf);
        assertEquals(sent, CaseLedgerS2CPacket.decode(buf));
    }

    // ------------------------------------------------------------------ self application (R10)

    /**
     * The self-application request carries two bounded values and no identity at all.
     *
     * <p>Deliberately so: the subject is the connection's player, so there is no field here that could
     * name somebody else, and the worst a forged packet can ask for is a slot the sender's own rig or
     * held item does not support — which the server refuses.
     */
    @Test
    void aSelfRestraintRequestRoundTripsEverySlotAndEitherHand() {
        for (RestraintSlot slot : RestraintSlot.values()) {
            for (boolean offHand : new boolean[]{false, true}) {
                SelfRestraintC2SPacket sent = new SelfRestraintC2SPacket(slot, offHand);
                FriendlyByteBuf buf = buffer();
                SelfRestraintC2SPacket.encode(sent, buf);
                assertEquals(sent, SelfRestraintC2SPacket.decode(buf));
                assertEquals(0, buf.readableBytes());
            }
        }
    }

    /**
     * An out-of-range slot byte is a decode failure, not an arms restraint (0.7.5 M3).
     *
     * <p>This is the rule the 1.21.1 port applies through {@code CrimeStreamCodecs.enumCodec}, and the
     * baseline now applies it too, so a reader comparing the two lines sees one policy. Clamping was
     * the wrong answer for exactly this packet: it chooses <em>where a restraint goes</em>, and a
     * substituted default is a decision the player never made.
     */
    @Test
    void anUnknownSelfRestraintSlotIsRefused() {
        FriendlyByteBuf buf = buffer();
        buf.writeByte(97);
        buf.writeBoolean(false);
        assertThrows(DecoderException.class, () -> SelfRestraintC2SPacket.decode(buf));
    }

    /** The same rule on the struggle packet, whose slot decides which restraint takes the damage. */
    @Test
    void anUnknownStruggleSlotIsRefused() {
        FriendlyByteBuf buf = buffer();
        buf.writeLong(1L);
        buf.writeByte(200);
        buf.writeByte(0);
        buf.writeVarInt(1);
        assertThrows(DecoderException.class, () -> RestraintStruggleC2SPacket.decode(buf));
    }

    @Test
    void aWellFormedStruggleStillRoundTrips() {
        for (RestraintSlot slot : RestraintSlot.values()) {
            RestraintStruggleC2SPacket sent = new RestraintStruggleC2SPacket(7L, slot, 1, 3);
            FriendlyByteBuf buf = buffer();
            RestraintStruggleC2SPacket.encode(sent, buf);
            assertEquals(sent, RestraintStruggleC2SPacket.decode(buf));
            assertEquals(0, buf.readableBytes());
        }
    }

    /** And on the one M3 packet that carries an enum at all. */
    @Test
    void anUnknownLockpickOutcomeIsRefused() {
        FriendlyByteBuf buf = buffer();
        buf.writeLong(4L);
        buf.writeByte(9);
        assertThrows(DecoderException.class, () -> LockpickResultS2CPacket.decode(buf));

        for (LockpickOutcome outcome : LockpickOutcome.values()) {
            LockpickResultS2CPacket sent = new LockpickResultS2CPacket(4L, outcome);
            FriendlyByteBuf round = buffer();
            LockpickResultS2CPacket.encode(sent, round);
            assertEquals(sent, LockpickResultS2CPacket.decode(round));
            assertEquals(0, round.readableBytes());
        }
    }

    /** The ordinal helper itself: in range is a value, out of range is empty, never a default. */
    @Test
    void theOrdinalHelperRefusesRatherThanSubstituting() {
        FriendlyByteBuf good = buffer();
        PacketBounds.writeEnumOrdinal(good, RestraintSlot.LEGS);
        assertEquals(java.util.Optional.of(RestraintSlot.LEGS),
                PacketBounds.readEnumOrdinal(good, RestraintSlot.class));

        FriendlyByteBuf bad = buffer();
        bad.writeByte(RestraintSlot.values().length);
        assertTrue(PacketBounds.readEnumOrdinal(bad, RestraintSlot.class).isEmpty());
    }

    /** A lockpick attempt is three bounded numbers, and the phase is clamped into a sane range. */
    @Test
    void lockpickAttemptsAreBoundedAndCarryNoIdentity() {
        LockpickAttemptC2SPacket huge = new LockpickAttemptC2SPacket(1L, Integer.MAX_VALUE, 400_000);
        assertEquals(PacketBounds.MAX_LOCKPICK_PHASE, huge.phase());
        assertEquals(40_000, huge.angleMilliDegrees(), "angles wrap into one turn");
        assertEquals(359_000, new LockpickAttemptC2SPacket(1L, 0, -1_000).angleMilliDegrees());

        FriendlyByteBuf buf = buffer();
        LockpickAttemptC2SPacket sent = new LockpickAttemptC2SPacket(12L, 3, 180_000);
        LockpickAttemptC2SPacket.encode(sent, buf);
        assertEquals(sent, LockpickAttemptC2SPacket.decode(buf));
        assertEquals(0, buf.readableBytes());
        // Three fields and no more: no actor, no target, no outcome, no durability.
        assertEquals(3, LockpickAttemptC2SPacket.class.getRecordComponents().length);
    }
}
