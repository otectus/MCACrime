package dev.otectus.mcacrime.network;

import dev.otectus.mcacrime.McaCrime;
import dev.otectus.mcacrime.report.PlayerCrimeReportService;
import dev.otectus.mcacrime.report.PlayerReportStatus;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import java.util.List;
import java.util.UUID;

/** Private report choices issued by the server for one nearby responder. */
public record ReportMenuS2CPacket(PlayerCrimeReportService.Menu menu) implements CustomPacketPayload {

    public static final Type<ReportMenuS2CPacket> TYPE = new Type<>(McaCrime.id("report_menu"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ReportMenuS2CPacket> STREAM_CODEC =
            StreamCodec.of((buf, packet) -> packet.write(buf), ReportMenuS2CPacket::read);

    private void write(RegistryFriendlyByteBuf buf) {
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

    private static ReportMenuS2CPacket read(RegistryFriendlyByteBuf buf) {
        UUID id = buf.readUUID();
        int revision = buf.readVarInt();
        UUID responder = buf.readUUID();
        long issuedAt = buf.readVarLong();
        List<PlayerCrimeReportService.Choice> rows = PacketBounds.readBoundedList(buf,
                PlayerCrimeReportService.MAX_CHOICES, row -> readChoice((RegistryFriendlyByteBuf) row));
        String empty = buf.readBoolean() ? PacketBounds.readId(buf) : null;
        return new ReportMenuS2CPacket(new PlayerCrimeReportService.Menu(id, revision, responder,
                issuedAt, rows, empty));
    }

    static void writeChoice(RegistryFriendlyByteBuf buf, PlayerCrimeReportService.Choice row) {
        buf.writeUUID(row.evidenceId());
        buf.writeUUID(row.caseId());
        buf.writeBoolean(row.suspectId() != null);
        if (row.suspectId() != null) buf.writeUUID(row.suspectId());
        ComponentSerialization.STREAM_CODEC.encode(buf, row.suspectName());
        buf.writeResourceLocation(row.incidentType());
        ComponentSerialization.STREAM_CODEC.encode(buf, row.area());
        buf.writeVarLong(row.occurredAt());
        buf.writeUtf(row.status().name(), PacketBounds.MAX_ID_LENGTH);
    }

    static PlayerCrimeReportService.Choice readChoice(RegistryFriendlyByteBuf buf) {
        UUID evidence = buf.readUUID();
        UUID caseId = buf.readUUID();
        UUID suspect = buf.readBoolean() ? buf.readUUID() : null;
        Component name = ComponentSerialization.STREAM_CODEC.decode(buf);
        var type = PacketBounds.readResourceLocation(buf);
        Component area = ComponentSerialization.STREAM_CODEC.decode(buf);
        long occurred = buf.readVarLong();
        // A status the client does not know is shown as expired, never refused: it is display only.
        PlayerReportStatus status = PacketBounds.readEnum(buf, PlayerReportStatus.class)
                .orElse(PlayerReportStatus.EXPIRED);
        return new PlayerCrimeReportService.Choice(evidence, caseId, suspect, name, type, area, occurred, status);
    }

    /** The shared list reader, for the receipt projection that carries the same rows. */
    static List<PlayerCrimeReportService.Choice> readChoices(FriendlyByteBuf buf) {
        return PacketBounds.readBoundedList(buf, PlayerCrimeReportService.MAX_CHOICES,
                row -> readChoice((RegistryFriendlyByteBuf) row));
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
