package dev.otectus.mcacrime.network;

import dev.otectus.mcacrime.client.CrimeClientHandlers;
import dev.otectus.mcacrime.report.PlayerCrimeReportService;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.List;
import java.util.function.Supplier;

/** Private read-only projection of the receiver's own report receipts. */
public record PlayerReportsS2CPacket(List<PlayerCrimeReportService.Choice> reports) {
    public PlayerReportsS2CPacket { reports = reports == null ? List.of() : List.copyOf(reports); }
    public static void encode(PlayerReportsS2CPacket msg, FriendlyByteBuf buf) {
        List<PlayerCrimeReportService.Choice> rows = msg.reports.stream()
                .limit(PlayerCrimeReportService.MAX_CHOICES).toList();
        buf.writeVarInt(rows.size());
        rows.forEach(row -> ReportMenuS2CPacket.writeChoice(buf, row));
    }
    public static PlayerReportsS2CPacket decode(FriendlyByteBuf buf) {
        return new PlayerReportsS2CPacket(PacketBounds.readBoundedList(buf,
                PlayerCrimeReportService.MAX_CHOICES, ReportMenuS2CPacket::readChoice));
    }
    public static void handle(PlayerReportsS2CPacket msg, Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context context = ctx.get(); context.setPacketHandled(true);
        if (!context.getDirection().getReceptionSide().isClient()) return;
        context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> CrimeClientHandlers.onPlayerReports(msg)));
    }
}
