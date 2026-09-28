package dev.otectus.mcacrime.network;

import dev.otectus.mcacrime.report.PlayerCrimeReportService;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/** Requests only the authenticated sender's submitted report receipts. */
public record RequestPlayerReportsC2SPacket() {
    public static void encode(RequestPlayerReportsC2SPacket msg, FriendlyByteBuf buf) { }
    public static RequestPlayerReportsC2SPacket decode(FriendlyByteBuf buf) { return new RequestPlayerReportsC2SPacket(); }
    public static void handle(RequestPlayerReportsC2SPacket msg, Supplier<NetworkEvent.Context> ctx) {
        ServerPacketGuard.accept(ctx, RequestBudget.Category.REPORT,
                player -> CrimeNetwork.sendPlayerReports(player,
                        new PlayerReportsS2CPacket(PlayerCrimeReportService.reports(player))));
    }
}
