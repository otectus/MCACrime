package dev.otectus.mcacrime;

import dev.otectus.mcacrime.crime.Band;
import dev.otectus.mcacrime.enforcement.ChallengeResponse;
import dev.otectus.mcacrime.ledger.Resolution;
import dev.otectus.mcacrime.network.BandBulkSyncS2CPacket;
import dev.otectus.mcacrime.network.CaseLedgerS2CPacket;
import dev.otectus.mcacrime.network.GuardChallengeResponseC2SPacket;
import dev.otectus.mcacrime.network.PacketBounds;
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
}
