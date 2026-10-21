package dev.otectus.mcacrime.network;

import dev.otectus.mcacrime.McaCrime;
import dev.otectus.mcacrime.report.PlayerCrimeReportService;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import java.util.List;

/** Private read-only projection of the receiver's own report receipts. */
public record PlayerReportsS2CPacket(List<PlayerCrimeReportService.Choice> reports) implements CustomPacketPayload {

    public static final Type<PlayerReportsS2CPacket> TYPE = new Type<>(McaCrime.id("player_reports"));

    public static final StreamCodec<RegistryFriendlyByteBuf, PlayerReportsS2CPacket> STREAM_CODEC =
            StreamCodec.of((buf, packet) -> packet.write(buf), PlayerReportsS2CPacket::read);

    public PlayerReportsS2CPacket { reports = reports == null ? List.of() : List.copyOf(reports); }

    private void write(RegistryFriendlyByteBuf buf) {
        List<PlayerCrimeReportService.Choice> rows = reports.stream()
                .limit(PlayerCrimeReportService.MAX_CHOICES).toList();
        buf.writeVarInt(rows.size());
        rows.forEach(row -> ReportMenuS2CPacket.writeChoice(buf, row));
    }

    private static PlayerReportsS2CPacket read(RegistryFriendlyByteBuf buf) {
        return new PlayerReportsS2CPacket(ReportMenuS2CPacket.readChoices(buf));
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
