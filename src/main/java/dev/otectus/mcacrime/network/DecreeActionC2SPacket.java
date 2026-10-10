package dev.otectus.mcacrime.network;

import dev.otectus.mcacrime.McaCrime;
import dev.otectus.mcacrime.decree.DecreeMenuService;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.UUID;

/**
 * A ruler's answer to the decree screen (0.7.8): an intent, never an authority. The server re-derives the
 * throne, the target's standing and the store; this payload only names which offered row was chosen. An
 * operation name no build ever wrote decodes as none, which the session refuses as stale.
 */
public record DecreeActionC2SPacket(UUID nonce, UUID menuId, int revision, DecreeMenuService.Op op, UUID subject,
                                    String reason) implements CustomPacketPayload {

    public static final Type<DecreeActionC2SPacket> TYPE = new Type<>(McaCrime.id("decree_action"));

    public static final StreamCodec<RegistryFriendlyByteBuf, DecreeActionC2SPacket> STREAM_CODEC =
            StreamCodec.of((buf, packet) -> packet.write(buf), DecreeActionC2SPacket::read);

    public DecreeActionC2SPacket {
        reason = reason == null ? "" : reason;
    }

    private void write(RegistryFriendlyByteBuf buf) {
        buf.writeUUID(nonce);
        buf.writeUUID(menuId);
        buf.writeVarInt(revision);
        buf.writeUtf(op == null ? "" : op.name(), PacketBounds.MAX_ID_LENGTH);
        buf.writeUUID(subject);
        buf.writeUtf(reason, PacketBounds.MAX_ID_LENGTH);
    }

    private static DecreeActionC2SPacket read(RegistryFriendlyByteBuf buf) {
        UUID nonce = buf.readUUID();
        UUID menu = buf.readUUID();
        int revision = buf.readVarInt();
        DecreeMenuService.Op op = PacketBounds.readEnum(buf, DecreeMenuService.Op.class).orElse(null);
        UUID subject = buf.readUUID();
        String reason = PacketBounds.readId(buf);
        return new DecreeActionC2SPacket(nonce, menu, revision, op, subject, reason);
    }

    public static void handle(DecreeActionC2SPacket msg, IPayloadContext context) {
        ServerPacketGuard.accept(context, RequestBudget.Category.ACTION, player -> {
            var outcome = DecreeMenuService.submit(player, msg.menuId, msg.revision, msg.nonce, msg.op, msg.subject,
                    msg.reason);
            if (!outcome.ok()) {
                player.sendSystemMessage(Component.translatable(outcome.refusal().messageKey()));
            }
        });
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
