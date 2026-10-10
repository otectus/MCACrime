package dev.otectus.mcacrime.network;

import dev.otectus.mcacrime.decree.DecreeMenuService;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraftforge.network.NetworkEvent;

import java.util.UUID;
import java.util.function.Supplier;

/**
 * A ruler's answer to the decree screen (0.7.8): an intent, never an authority. The server re-derives the
 * throne, the target's standing and the store; this packet only names which offered row was chosen.
 */
public record DecreeActionC2SPacket(UUID nonce, UUID menuId, int revision, DecreeMenuService.Op op, UUID subject,
                                    String reason) {

    public static void encode(DecreeActionC2SPacket msg, FriendlyByteBuf buf) {
        buf.writeUUID(msg.nonce);
        buf.writeUUID(msg.menuId);
        buf.writeVarInt(msg.revision);
        buf.writeUtf(msg.op.name(), PacketBounds.MAX_ID_LENGTH);
        buf.writeUUID(msg.subject);
        buf.writeUtf(msg.reason == null ? "" : msg.reason, PacketBounds.MAX_ID_LENGTH);
    }

    public static DecreeActionC2SPacket decode(FriendlyByteBuf buf) {
        UUID nonce = buf.readUUID();
        UUID menu = buf.readUUID();
        int revision = buf.readVarInt();
        DecreeMenuService.Op op = PacketBounds.readEnum(buf, DecreeMenuService.Op.class).orElse(null);
        UUID subject = buf.readUUID();
        String reason = PacketBounds.readId(buf);
        return new DecreeActionC2SPacket(nonce, menu, revision, op, subject, reason);
    }

    public static void handle(DecreeActionC2SPacket msg, Supplier<NetworkEvent.Context> ctx) {
        ServerPacketGuard.accept(ctx, RequestBudget.Category.ACTION, player -> {
            var outcome = DecreeMenuService.submit(player, msg.menuId, msg.revision, msg.nonce, msg.op, msg.subject,
                    msg.reason);
            if (!outcome.ok()) {
                player.sendSystemMessage(Component.translatable(outcome.refusal().messageKey()));
            }
        });
    }
}
