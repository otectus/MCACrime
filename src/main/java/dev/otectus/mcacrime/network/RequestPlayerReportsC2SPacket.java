package dev.otectus.mcacrime.network;

import dev.otectus.mcacrime.McaCrime;
import dev.otectus.mcacrime.report.PlayerCrimeReportService;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** Requests only the authenticated sender's submitted report receipts. */
public record RequestPlayerReportsC2SPacket() implements CustomPacketPayload {

    public static final Type<RequestPlayerReportsC2SPacket> TYPE =
            new Type<>(McaCrime.id("request_player_reports"));

    /** No fields, so nothing goes on the wire but the payload id itself. */
    public static final StreamCodec<RegistryFriendlyByteBuf, RequestPlayerReportsC2SPacket> STREAM_CODEC =
            StreamCodec.unit(new RequestPlayerReportsC2SPacket());

    static void handle(RequestPlayerReportsC2SPacket msg, IPayloadContext context) {
        ServerPacketGuard.accept(context, RequestBudget.Category.REPORT,
                player -> CrimeNetwork.sendPlayerReports(player,
                        new PlayerReportsS2CPacket(PlayerCrimeReportService.reports(player))));
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
