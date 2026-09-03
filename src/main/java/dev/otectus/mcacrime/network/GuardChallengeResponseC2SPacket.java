package dev.otectus.mcacrime.network;

import dev.otectus.mcacrime.enforcement.ChallengeResponse;
import dev.otectus.mcacrime.enforcement.GuardChallengeService;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.UUID;
import java.util.function.Supplier;

/**
 * A player answering a guard challenge.
 *
 * <p>One of only three packets the client is allowed to send, and like the other two it carries an
 * <em>intent plus an identity</em>, never a result. The encounter id is what makes it answerable: the
 * server matches it against the challenge it actually opened, so a replayed or forged packet answers
 * an encounter that no longer exists and does nothing at all.
 *
 * <p>The response ordinal is clamped on read rather than validated by throwing. A decode that throws
 * drops the connection, and the safe substitute here is {@code REFUSE} — the same thing not answering
 * would have produced.
 */
public record GuardChallengeResponseC2SPacket(UUID encounterId, ChallengeResponse response) {

    public GuardChallengeResponseC2SPacket {
        encounterId = encounterId == null ? new UUID(0L, 0L) : encounterId;
        response = response == null ? ChallengeResponse.REFUSE : response;
    }

    public static void encode(GuardChallengeResponseC2SPacket msg, FriendlyByteBuf buf) {
        buf.writeUUID(msg.encounterId);
        buf.writeVarInt(msg.response.ordinal());
    }

    public static GuardChallengeResponseC2SPacket decode(FriendlyByteBuf buf) {
        return new GuardChallengeResponseC2SPacket(buf.readUUID(),
                ChallengeResponse.byOrdinal(buf.readVarInt()));
    }

    public static void handle(GuardChallengeResponseC2SPacket msg, Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context context = ctx.get();
        context.enqueueWork(() -> {
            ServerPlayer sender = context.getSender();
            if (sender != null) {
                GuardChallengeService.respond(sender, msg.encounterId(), msg.response());
            }
        });
        context.setPacketHandled(true);
    }
}
