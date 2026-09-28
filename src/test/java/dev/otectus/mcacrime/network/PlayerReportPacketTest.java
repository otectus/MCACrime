package dev.otectus.mcacrime.network;

import dev.otectus.mcacrime.report.PlayerCrimeReportService;
import dev.otectus.mcacrime.report.PlayerReportStatus;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class PlayerReportPacketTest {
    @Test
    void unknownSuspectAndServerIssuedAuthorizationRoundTrip() {
        var choice = new PlayerCrimeReportService.Choice(UUID.randomUUID(), UUID.randomUUID(), null,
                Component.translatable("mcacrime.report.unidentified"),
                new ResourceLocation("mcacrime", "attempted_mugging"), Component.literal("Riverside"),
                10L, PlayerReportStatus.ELIGIBLE);
        var menu = new PlayerCrimeReportService.Menu(UUID.randomUUID(), 3, UUID.randomUUID(), 20L,
                List.of(choice), null);
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            ReportMenuS2CPacket.encode(new ReportMenuS2CPacket(menu), buffer);
            var decoded = ReportMenuS2CPacket.decode(buffer).menu();
            assertEquals(menu.id(), decoded.id());
            assertNull(decoded.choices().get(0).suspectId());
            assertEquals(PlayerReportStatus.ELIGIBLE, decoded.choices().get(0).status());
            assertEquals(0, buffer.readableBytes());
        } finally { buffer.release(); }
    }

    @Test
    void responseCapsAtTwentyRows() {
        List<PlayerCrimeReportService.Choice> choices = new ArrayList<>();
        for (int i = 0; i < PlayerCrimeReportService.MAX_CHOICES + 5; i++) {
            choices.add(new PlayerCrimeReportService.Choice(UUID.randomUUID(), UUID.randomUUID(), null,
                    Component.literal("Unknown"), new ResourceLocation("mcacrime", "mugging"),
                    Component.literal("Wilderness"), i, PlayerReportStatus.ELIGIBLE));
        }
        var menu = new PlayerCrimeReportService.Menu(UUID.randomUUID(), 1, UUID.randomUUID(), 0L,
                choices, null);
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            ReportMenuS2CPacket.encode(new ReportMenuS2CPacket(menu), buffer);
            assertEquals(PlayerCrimeReportService.MAX_CHOICES,
                    ReportMenuS2CPacket.decode(buffer).menu().choices().size());
        } finally { buffer.release(); }
    }

    @Test
    void submitCarriesOnlySessionEvidenceResponderAndNonce() {
        var packet = new SubmitPlayerReportC2SPacket(UUID.randomUUID(), UUID.randomUUID(), 4,
                UUID.randomUUID(), UUID.randomUUID());
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            SubmitPlayerReportC2SPacket.encode(packet, buffer);
            assertEquals(packet, SubmitPlayerReportC2SPacket.decode(buffer));
            assertEquals(0, buffer.readableBytes());
        } finally { buffer.release(); }
    }
}
