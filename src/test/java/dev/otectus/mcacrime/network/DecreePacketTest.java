package dev.otectus.mcacrime.network;

import dev.otectus.mcacrime.decree.DecreeMenuService;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The decree screen's packets (0.7.8): exact round trips, bounded lists, and no unknown operation. */
class DecreePacketTest {

    private static DecreeMenuService.Menu menu(int candidates, int open) {
        List<DecreeMenuService.Candidate> rows = new ArrayList<>();
        for (int i = 0; i < candidates; i++) {
            rows.add(new DecreeMenuService.Candidate(UUID.randomUUID(), Component.literal("P" + i), i % 2 == 0,
                    i % 2 == 0 ? "" : "mcacrime.decree.refused.target_noble"));
        }
        List<DecreeMenuService.OpenRow> decrees = new ArrayList<>();
        for (int i = 0; i < open; i++) {
            decrees.add(new DecreeMenuService.OpenRow(UUID.randomUUID(), Component.literal("D" + i), "treason", 600L * i));
        }
        return new DecreeMenuService.Menu(UUID.randomUUID(), 1, UUID.randomUUID(), Component.literal("Riverside"),
                rows, decrees, 72000L, Component.literal("32 emeralds"), true);
    }

    @Test
    void theMenuRoundTrips() {
        DecreeMenuService.Menu menu = menu(3, 2);
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            DecreeMenuS2CPacket.encode(new DecreeMenuS2CPacket(menu), buffer);
            DecreeMenuService.Menu decoded = DecreeMenuS2CPacket.decode(buffer).menu();
            assertEquals(0, buffer.readableBytes());
            assertEquals(menu.id(), decoded.id());
            assertEquals(menu.hostId(), decoded.hostId());
            assertEquals(3, decoded.candidates().size());
            assertEquals(menu.candidates().get(1).player(), decoded.candidates().get(1).player());
            assertFalse(decoded.candidates().get(1).eligible());
            assertEquals("mcacrime.decree.refused.target_noble", decoded.candidates().get(1).reasonKey());
            assertEquals(2, decoded.open().size());
            assertEquals(menu.open().get(1).decree(), decoded.open().get(1).decree());
            assertEquals(600L, decoded.open().get(1).remainingTicks());
            assertEquals(72000L, decoded.durationTicks());
            assertTrue(decoded.payable());
        } finally {
            buffer.release();
        }
    }

    @Test
    void theMenuIsBoundedOnBothSides() {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            DecreeMenuS2CPacket.encode(new DecreeMenuS2CPacket(menu(DecreeMenuService.MAX_CANDIDATES + 9,
                    DecreeMenuService.MAX_OPEN + 4)), buffer);
            DecreeMenuService.Menu decoded = DecreeMenuS2CPacket.decode(buffer).menu();
            assertEquals(DecreeMenuService.MAX_CANDIDATES, decoded.candidates().size());
            assertEquals(DecreeMenuService.MAX_OPEN, decoded.open().size());
        } finally {
            buffer.release();
        }
    }

    @Test
    void theAnswerRoundTripsAndAnUnknownOperationDecodesAsNone() {
        DecreeActionC2SPacket packet = new DecreeActionC2SPacket(UUID.randomUUID(), UUID.randomUUID(), 1,
                DecreeMenuService.Op.WITHDRAW, UUID.randomUUID(), "");
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            DecreeActionC2SPacket.encode(packet, buffer);
            assertEquals(packet, DecreeActionC2SPacket.decode(buffer));
            assertEquals(0, buffer.readableBytes());
            buffer.clear();
            buffer.writeUUID(UUID.randomUUID());
            buffer.writeUUID(UUID.randomUUID());
            buffer.writeVarInt(1);
            buffer.writeUtf("PROCLAIM");
            buffer.writeUUID(UUID.randomUUID());
            buffer.writeUtf("treason");
            assertNull(DecreeActionC2SPacket.decode(buffer).op(), "a forged operation must not map to a real one");
        } finally {
            buffer.release();
        }
    }
}
