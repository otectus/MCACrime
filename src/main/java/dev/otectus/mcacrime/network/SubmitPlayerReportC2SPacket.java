package dev.otectus.mcacrime.network;

import dev.otectus.mcacrime.report.PlayerCrimeReportService;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.UUID;
import java.util.function.Supplier;

/** Intent packet: all report facts are re-derived from the server-issued menu and evidence id. */
public record SubmitPlayerReportC2SPacket(UUID nonce, UUID menuId, int revision,
                                          UUID responderId, UUID evidenceId) {
    public static void encode(SubmitPlayerReportC2SPacket msg, FriendlyByteBuf buf) {
        buf.writeUUID(msg.nonce); buf.writeUUID(msg.menuId); buf.writeVarInt(msg.revision);
        buf.writeUUID(msg.responderId); buf.writeUUID(msg.evidenceId);
    }

    public static SubmitPlayerReportC2SPacket decode(FriendlyByteBuf buf) {
        return new SubmitPlayerReportC2SPacket(buf.readUUID(), buf.readUUID(), buf.readVarInt(),
                buf.readUUID(), buf.readUUID());
    }

    public static void handle(SubmitPlayerReportC2SPacket msg, Supplier<NetworkEvent.Context> ctx) {
        ServerPacketGuard.accept(ctx, RequestBudget.Category.REPORT,
                player -> PlayerCrimeReportService.submit(player, msg.menuId, msg.revision,
                        msg.responderId, msg.evidenceId, msg.nonce));
    }
}
