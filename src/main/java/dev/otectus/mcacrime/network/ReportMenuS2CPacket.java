package dev.otectus.mcacrime.network;

import dev.otectus.mcacrime.client.CrimeClientHandlers;
import dev.otectus.mcacrime.report.PlayerCrimeReportService;
import dev.otectus.mcacrime.report.PlayerReportStatus;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

/** Private report choices issued by the server for one nearby responder. */
public record ReportMenuS2CPacket(PlayerCrimeReportService.Menu menu) {
    public static void encode(ReportMenuS2CPacket msg, FriendlyByteBuf buf) {
        var menu = msg.menu;
        buf.writeUUID(menu.id());
        buf.writeVarInt(menu.revision());
        buf.writeUUID(menu.responderId());
        buf.writeVarLong(menu.issuedAt());
        List<PlayerCrimeReportService.Choice> rows = menu.choices().stream()
                .limit(PlayerCrimeReportService.MAX_CHOICES).toList();
        buf.writeVarInt(rows.size());
        rows.forEach(row -> writeChoice(buf, row));
        buf.writeBoolean(menu.emptyReasonKey() != null);
        if (menu.emptyReasonKey() != null) buf.writeUtf(menu.emptyReasonKey(), PacketBounds.MAX_ID_LENGTH);
    }

    public static ReportMenuS2CPacket decode(FriendlyByteBuf buf) {
        UUID id = buf.readUUID();
        int revision = buf.readVarInt();
        UUID responder = buf.readUUID();
        long issuedAt = buf.readVarLong();
        List<PlayerCrimeReportService.Choice> rows = PacketBounds.readBoundedList(buf,
                PlayerCrimeReportService.MAX_CHOICES, ReportMenuS2CPacket::readChoice);
        String empty = buf.readBoolean() ? PacketBounds.readId(buf) : null;
        return new ReportMenuS2CPacket(new PlayerCrimeReportService.Menu(id, revision, responder,
                issuedAt, rows, empty));
    }

    static void writeChoice(FriendlyByteBuf buf, PlayerCrimeReportService.Choice row) {
        buf.writeUUID(row.evidenceId());
        buf.writeUUID(row.caseId());
        buf.writeBoolean(row.suspectId() != null);
        if (row.suspectId() != null) buf.writeUUID(row.suspectId());
        buf.writeComponent(row.suspectName());
        buf.writeResourceLocation(row.incidentType());
        buf.writeComponent(row.area());
        buf.writeVarLong(row.occurredAt());
        buf.writeUtf(row.status().name(), PacketBounds.MAX_ID_LENGTH);
    }

    static PlayerCrimeReportService.Choice readChoice(FriendlyByteBuf buf) {
        UUID evidence = buf.readUUID();
        UUID caseId = buf.readUUID();
        UUID suspect = buf.readBoolean() ? buf.readUUID() : null;
        var name = buf.readComponent();
        var type = PacketBounds.readResourceLocation(buf);
        var area = buf.readComponent();
        long occurred = buf.readVarLong();
        PlayerReportStatus status = PacketBounds.readEnum(buf, PlayerReportStatus.class)
                .orElse(PlayerReportStatus.EXPIRED);
        return new PlayerCrimeReportService.Choice(evidence, caseId, suspect, name, type, area, occurred, status);
    }

    public static void handle(ReportMenuS2CPacket msg, Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context context = ctx.get();
        context.setPacketHandled(true);
        if (!context.getDirection().getReceptionSide().isClient()) return;
        context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> CrimeClientHandlers.onReportMenu(msg)));
    }
}
