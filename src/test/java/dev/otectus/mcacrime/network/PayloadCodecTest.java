package dev.otectus.mcacrime.network;

import dev.otectus.mcacrime.action.ActionCategory;
import dev.otectus.mcacrime.action.ActionDuration;
import dev.otectus.mcacrime.action.ActionLegality;
import dev.otectus.mcacrime.action.ActionMenuKind;
import dev.otectus.mcacrime.crime.Band;
import dev.otectus.mcacrime.enforcement.ChallengeResponse;
import dev.otectus.mcacrime.enforcement.RestraintVisualState;
import dev.otectus.mcacrime.enforcement.RestraintVisualType;
import dev.otectus.mcacrime.item.weapon.WeaponPolicySnapshot;
import dev.otectus.mcacrime.job.CriminalJob;
import dev.otectus.mcacrime.ledger.Resolution;
import io.netty.buffer.Unpooled;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every payload must survive its own codec, and must refuse anything a hostile peer could send
 * (spec §9.6).
 *
 * <p>The Forge decoders clamped: an over-long list was truncated and a bad enum ordinal became a
 * default, because a throw on the network thread cost the whole connection. A payload decode failure
 * is scoped to the payload, so these decoders throw instead, and the second half of this class is
 * what says so.
 */
class PayloadCodecTest {

    private static RegistryFriendlyByteBuf buffer() {
        return new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
    }

    private static <T> T roundTrip(StreamCodec<RegistryFriendlyByteBuf, T> codec, T value) {
        RegistryFriendlyByteBuf buf = buffer();
        codec.encode(buf, value);
        T decoded = codec.decode(buf);
        assertEquals(0, buf.readableBytes(), "decoder left bytes unread; the stream would desynchronise");
        return decoded;
    }

    private static String repeat(int length) {
        return "x".repeat(length);
    }

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath("mcacrime", path);
    }

    // --- the five server-bound payloads -----------------------------------------------------------

    @Test
    void requestActionMenuRoundTrips() {
        RequestActionMenuC2SPacket packet = new RequestActionMenuC2SPacket(UUID.randomUUID());
        assertEquals(packet, roundTrip(RequestActionMenuC2SPacket.STREAM_CODEC, packet));
    }

    @Test
    void startActionRoundTrips() {
        StartActionC2SPacket packet = new StartActionC2SPacket(UUID.randomUUID(), UUID.randomUUID(), 7,
                id("mug"), UUID.randomUUID());
        assertEquals(packet, roundTrip(StartActionC2SPacket.STREAM_CODEC, packet));
    }

    @Test
    void requestSelfMenuRoundTripsEveryKind() {
        for (ActionMenuKind kind : ActionMenuKind.values()) {
            RequestSelfMenuC2SPacket packet = new RequestSelfMenuC2SPacket(kind);
            assertEquals(packet, roundTrip(RequestSelfMenuC2SPacket.STREAM_CODEC, packet));
        }
    }

    @Test
    void guardChallengeResponseRoundTripsEveryResponse() {
        for (ChallengeResponse response : ChallengeResponse.values()) {
            GuardChallengeResponseC2SPacket packet =
                    new GuardChallengeResponseC2SPacket(UUID.randomUUID(), response);
            assertEquals(packet, roundTrip(GuardChallengeResponseC2SPacket.STREAM_CODEC, packet));
        }
    }

    @Test
    void requestCaseLedgerWritesNothingButItsId() {
        RegistryFriendlyByteBuf buf = buffer();
        RequestCaseLedgerC2SPacket.STREAM_CODEC.encode(buf, new RequestCaseLedgerC2SPacket());
        assertEquals(0, buf.readableBytes(), "an argument-free request should put no payload on the wire");
        assertEquals(new RequestCaseLedgerC2SPacket(), RequestCaseLedgerC2SPacket.STREAM_CODEC.decode(buf));
    }

    // --- the twelve client-bound payloads ------------------------------------------------------------

    @Test
    void selfStatusRoundTripsEveryBand() {
        for (Band band : Band.values()) {
            SelfStatusS2CPacket packet =
                    new SelfStatusS2CPacket(-4_000L, 12_345L, band, true, 6_000L, false);
            assertEquals(packet, roundTrip(SelfStatusS2CPacket.STREAM_CODEC, packet));
        }
    }

    @Test
    void bandSyncRoundTrips() {
        BandSyncS2CPacket packet = new BandSyncS2CPacket(UUID.randomUUID(), Band.values()[0]);
        assertEquals(packet, roundTrip(BandSyncS2CPacket.STREAM_CODEC, packet));
    }

    @Test
    void bandBulkSyncRoundTripsEmptyAndFull() {
        assertEquals(Map.of(),
                roundTrip(BandBulkSyncS2CPacket.STREAM_CODEC, new BandBulkSyncS2CPacket(Map.of())).bands());

        Map<UUID, Band> full = new HashMap<>();
        for (int i = 0; i < BandBulkSyncS2CPacket.MAX_PLAYERS; i++) {
            full.put(UUID.randomUUID(), Band.values()[i % Band.values().length]);
        }
        assertEquals(full,
                roundTrip(BandBulkSyncS2CPacket.STREAM_CODEC, new BandBulkSyncS2CPacket(full)).bands());
    }

    @Test
    void captiveStatusRoundTripsEmptyAndMaximumCaptorName() {
        CaptiveStatusS2CPacket free = new CaptiveStatusS2CPacket(false, false, "", 0L);
        assertEquals(free, roundTrip(CaptiveStatusS2CPacket.STREAM_CODEC, free));

        CaptiveStatusS2CPacket held = new CaptiveStatusS2CPacket(true, true,
                repeat(CaptiveStatusS2CPacket.MAX_CAPTOR_LENGTH), 72_000L);
        assertEquals(held, roundTrip(CaptiveStatusS2CPacket.STREAM_CODEC, held));
    }

    @Test
    void actionMenuRoundTripsEmptyAndFull() {
        ActionMenuS2CPacket empty = new ActionMenuS2CPacket(UUID.randomUUID(), 1, UUID.randomUUID(),
                ActionMenuKind.values()[0], Component.literal("Nadia"), List.of());
        assertEquals(empty, roundTrip(ActionMenuS2CPacket.STREAM_CODEC, empty));

        List<ActionMenuEntry> rows = new ArrayList<>();
        for (int i = 0; i < ActionMenuS2CPacket.MAX_ACTIONS; i++) {
            rows.add(entry(i));
        }
        ActionMenuS2CPacket full = new ActionMenuS2CPacket(UUID.randomUUID(), 3, UUID.randomUUID(),
                ActionMenuKind.values()[0], Component.literal("Nadia"), rows);
        assertEquals(full, roundTrip(ActionMenuS2CPacket.STREAM_CODEC, full));
    }

    private static ActionMenuEntry entry(int index) {
        List<String> requirements = new ArrayList<>();
        for (int r = 0; r < ActionMenuEntry.MAX_REQUIREMENTS; r++) {
            requirements.add("mcacrime.requirement." + r);
        }
        return new ActionMenuEntry(id("action_" + index),
                repeat(ActionMenuEntry.MAX_KEY_LENGTH),
                repeat(ActionMenuEntry.MAX_KEY_LENGTH),
                ActionCategory.values()[index % ActionCategory.values().length],
                ActionLegality.values()[index % ActionLegality.values().length],
                ActionDuration.values()[index % ActionDuration.values().length],
                requirements, true, false, repeat(ActionMenuEntry.MAX_REASON_LENGTH));
    }

    @Test
    void actionProgressRoundTripsEveryPhase() {
        for (ActionProgressS2CPacket.Phase phase : ActionProgressS2CPacket.Phase.values()) {
            ActionProgressS2CPacket packet = new ActionProgressS2CPacket(UUID.randomUUID(),
                    repeat(ActionProgressS2CPacket.MAX_LABEL_LENGTH), 3, 20, phase,
                    repeat(ActionProgressS2CPacket.MAX_OUTCOME_LENGTH),
                    Component.translatable("mcacrime.mug.success", 7));
            assertEquals(packet, roundTrip(ActionProgressS2CPacket.STREAM_CODEC, packet));
        }
        ActionProgressS2CPacket bare = new ActionProgressS2CPacket(UUID.randomUUID(), "", 0, 1, null, "",
                Component.empty());
        assertEquals(bare, roundTrip(ActionProgressS2CPacket.STREAM_CODEC, bare));

        // A null outcome text is normalised rather than written, so an older caller cannot NPE the encode.
        ActionProgressS2CPacket nullText = new ActionProgressS2CPacket(UUID.randomUUID(), "label", 1, 1,
                ActionProgressS2CPacket.Phase.FINISHED, "mcacrime.mug.empty", null);
        assertEquals(nullText, roundTrip(ActionProgressS2CPacket.STREAM_CODEC, nullText));
    }

    @Test
    void guardChallengeRoundTripsOpenAndClosed() {
        GuardChallengeS2CPacket closed = GuardChallengeS2CPacket.closed();
        assertEquals(closed, roundTrip(GuardChallengeS2CPacket.STREAM_CODEC, closed));

        GuardChallengeS2CPacket open = new GuardChallengeS2CPacket(true, UUID.randomUUID(),
                Component.literal("Guard Kolya"), Component.translatable("mcacrime.jurisdiction.none"),
                4, 250L, true, 600L);
        assertEquals(open, roundTrip(GuardChallengeS2CPacket.STREAM_CODEC, open));
    }

    @Test
    void caseLedgerRoundTripsEmptyAndFull() {
        CaseLedgerS2CPacket empty = new CaseLedgerS2CPacket(List.of(), 0, 0L);
        assertEquals(empty, roundTrip(CaseLedgerS2CPacket.STREAM_CODEC, empty));

        List<CaseLedgerS2CPacket.Row> rows = new ArrayList<>();
        for (int i = 0; i < CaseLedgerS2CPacket.MAX_ROWS; i++) {
            rows.add(new CaseLedgerS2CPacket.Row(UUID.randomUUID(), id("theft"),
                    Resolution.values()[i % Resolution.values().length], 1_000L + i, 25L, i % 2 == 0,
                    repeat(CaseLedgerS2CPacket.Row.MAX_COMMUNITY_LENGTH)));
        }
        CaseLedgerS2CPacket full = new CaseLedgerS2CPacket(rows, 12, 900L);
        assertEquals(full, roundTrip(CaseLedgerS2CPacket.STREAM_CODEC, full));
    }

    @Test
    void restraintSyncRoundTrips() {
        for (RestraintVisualType visual : RestraintVisualType.values()) {
            RestraintSyncS2CPacket packet =
                    new RestraintSyncS2CPacket(UUID.randomUUID(), true, visual, 4711);
            assertEquals(packet, roundTrip(RestraintSyncS2CPacket.STREAM_CODEC, packet));
        }
    }

    @Test
    void restraintBulkSyncRoundTripsEmptyAndFull() {
        assertEquals(Map.of(), roundTrip(RestraintBulkSyncS2CPacket.STREAM_CODEC,
                new RestraintBulkSyncS2CPacket(Map.of())).restrained());

        Map<UUID, RestraintVisualState> full = new HashMap<>();
        for (int i = 0; i < RestraintBulkSyncS2CPacket.MAX_SUBJECTS; i++) {
            full.put(UUID.randomUUID(), new RestraintVisualState(true,
                    RestraintVisualType.values()[i % RestraintVisualType.values().length], i));
        }
        assertEquals(full, roundTrip(RestraintBulkSyncS2CPacket.STREAM_CODEC,
                new RestraintBulkSyncS2CPacket(full)).restrained());
    }

    @Test
    void weaponPolicyRoundTrips() {
        WeaponPolicySnapshot policy = new WeaponPolicySnapshot(true, List.of("minecraft:stick"),
                List.of("minecraft:feather"), false, 4.5D, List.of("rifle"), List.of("tacz"), false);
        WeaponPolicyS2CPacket packet = new WeaponPolicyS2CPacket(policy);
        assertEquals(packet, roundTrip(WeaponPolicyS2CPacket.STREAM_CODEC, packet));
    }

    @Test
    void criminalJobSyncRoundTripsEveryJob() {
        for (CriminalJob job : CriminalJob.values()) {
            CriminalJobSyncS2CPacket packet = new CriminalJobSyncS2CPacket(UUID.randomUUID(), job);
            assertEquals(packet, roundTrip(CriminalJobSyncS2CPacket.STREAM_CODEC, packet));
        }
    }

    // --- hostile input ----------------------------------------------------------------------------

    @Test
    void anOversizedBandMapIsRefusedBeforeItIsAllocated() {
        RegistryFriendlyByteBuf buf = buffer();
        buf.writeVarInt(BandBulkSyncS2CPacket.MAX_PLAYERS + 1);
        assertThrows(RuntimeException.class, () -> BandBulkSyncS2CPacket.STREAM_CODEC.decode(buf));
    }

    @Test
    void anOversizedRestraintMapIsRefused() {
        RegistryFriendlyByteBuf buf = buffer();
        buf.writeVarInt(RestraintBulkSyncS2CPacket.MAX_SUBJECTS + 1);
        assertThrows(RuntimeException.class, () -> RestraintBulkSyncS2CPacket.STREAM_CODEC.decode(buf));
    }

    @Test
    void anOversizedActionMenuIsRefused() {
        RegistryFriendlyByteBuf buf = buffer();
        buf.writeUUID(UUID.randomUUID());
        buf.writeVarInt(1);
        buf.writeUUID(UUID.randomUUID());
        buf.writeVarInt(0);
        ComponentSerialization.STREAM_CODEC.encode(buf, Component.empty());
        buf.writeVarInt(ActionMenuS2CPacket.MAX_ACTIONS + 1);
        assertThrows(RuntimeException.class, () -> ActionMenuS2CPacket.STREAM_CODEC.decode(buf));
    }

    @Test
    void anOversizedCaseLedgerIsRefused() {
        RegistryFriendlyByteBuf buf = buffer();
        buf.writeVarInt(CaseLedgerS2CPacket.MAX_ROWS + 1);
        assertThrows(RuntimeException.class, () -> CaseLedgerS2CPacket.STREAM_CODEC.decode(buf));
    }

    @Test
    void anOversizedRequirementStripIsRefused() {
        RegistryFriendlyByteBuf buf = buffer();
        buf.writeResourceLocation(id("mug"));
        buf.writeUtf("label", ActionMenuEntry.MAX_KEY_LENGTH);
        buf.writeUtf("description", ActionMenuEntry.MAX_KEY_LENGTH);
        buf.writeVarInt(0);
        buf.writeVarInt(0);
        buf.writeVarInt(0);
        buf.writeVarInt(ActionMenuEntry.MAX_REQUIREMENTS + 1);
        assertThrows(RuntimeException.class, () -> ActionMenuEntry.STREAM_CODEC.decode(buf));
    }

    @Test
    void anOverlongStringIsRefused() {
        RegistryFriendlyByteBuf buf = buffer();
        buf.writeUUID(UUID.randomUUID());
        buf.writeUtf(repeat(ActionProgressS2CPacket.MAX_LABEL_LENGTH + 1));
        assertThrows(RuntimeException.class, () -> ActionProgressS2CPacket.STREAM_CODEC.decode(buf));
    }

    @Test
    void aBandOrdinalNoBuildEverWroteIsRefused() {
        RegistryFriendlyByteBuf buf = buffer();
        buf.writeLong(0L);
        buf.writeLong(0L);
        buf.writeVarInt(Band.values().length);
        assertThrows(RuntimeException.class, () -> SelfStatusS2CPacket.STREAM_CODEC.decode(buf));
    }

    @Test
    void aCriminalJobOrdinalNoBuildEverWroteIsRefused() {
        RegistryFriendlyByteBuf buf = buffer();
        buf.writeUUID(UUID.randomUUID());
        buf.writeVarInt(CriminalJob.values().length);
        assertThrows(RuntimeException.class, () -> CriminalJobSyncS2CPacket.STREAM_CODEC.decode(buf));
    }

    @Test
    void anActionPhaseOrdinalNoBuildEverWroteIsRefused() {
        RegistryFriendlyByteBuf buf = buffer();
        buf.writeUUID(UUID.randomUUID());
        buf.writeUtf("label", ActionProgressS2CPacket.MAX_LABEL_LENGTH);
        buf.writeVarInt(1);
        buf.writeVarInt(2);
        buf.writeVarInt(ActionProgressS2CPacket.Phase.values().length);
        assertThrows(RuntimeException.class, () -> ActionProgressS2CPacket.STREAM_CODEC.decode(buf));
    }

    @Test
    void aMalformedCrimeTypeIdIsRefused() {
        RegistryFriendlyByteBuf buf = buffer();
        buf.writeVarInt(1);
        buf.writeUUID(UUID.randomUUID());
        buf.writeUtf("Not A Valid Id");
        assertThrows(RuntimeException.class, () -> CaseLedgerS2CPacket.STREAM_CODEC.decode(buf));
    }

    @Test
    void negativeCountsAndDurationsAreNormalisedRatherThanTrusted() {
        CaseLedgerS2CPacket ledger = new CaseLedgerS2CPacket(List.of(), -5, -900L);
        assertEquals(0, ledger.totalOpen());
        assertEquals(0L, ledger.totalDue());

        GuardChallengeS2CPacket challenge =
                new GuardChallengeS2CPacket(true, null, null, null, -1, -1L, false, -1L);
        assertTrue(challenge.chargeCount() == 0 && challenge.assessedFine() == 0L
                && challenge.remainingTicks() == 0L);
    }
}
