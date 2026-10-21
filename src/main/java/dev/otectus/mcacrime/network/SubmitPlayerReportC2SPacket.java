package dev.otectus.mcacrime.network;

import dev.otectus.mcacrime.McaCrime;
import dev.otectus.mcacrime.report.PlayerCrimeReportService;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.UUID;

/** Intent packet: all report facts are re-derived from the server-issued menu and evidence id. */
public record SubmitPlayerReportC2SPacket(UUID nonce, UUID menuId, int revision,
                                          UUID responderId, UUID evidenceId) implements CustomPacketPayload {

    public static final Type<SubmitPlayerReportC2SPacket> TYPE = new Type<>(McaCrime.id("submit_player_report"));

    public static final StreamCodec<RegistryFriendlyByteBuf, SubmitPlayerReportC2SPacket> STREAM_CODEC =
            StreamCodec.composite(
                    UUIDUtil.STREAM_CODEC, SubmitPlayerReportC2SPacket::nonce,
                    UUIDUtil.STREAM_CODEC, SubmitPlayerReportC2SPacket::menuId,
                    ByteBufCodecs.VAR_INT, SubmitPlayerReportC2SPacket::revision,
                    UUIDUtil.STREAM_CODEC, SubmitPlayerReportC2SPacket::responderId,
                    UUIDUtil.STREAM_CODEC, SubmitPlayerReportC2SPacket::evidenceId,
                    SubmitPlayerReportC2SPacket::new);

    static void handle(SubmitPlayerReportC2SPacket msg, IPayloadContext context) {
        ServerPacketGuard.accept(context, RequestBudget.Category.REPORT,
                player -> PlayerCrimeReportService.submit(player, msg.menuId, msg.revision,
                        msg.responderId, msg.evidenceId, msg.nonce));
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
