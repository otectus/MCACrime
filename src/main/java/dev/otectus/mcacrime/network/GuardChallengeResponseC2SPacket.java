package dev.otectus.mcacrime.network;

import dev.otectus.mcacrime.enforcement.ChallengeResponse;
import dev.otectus.mcacrime.enforcement.GuardChallengeService;
import io.netty.handler.codec.DecoderException;
import net.minecraft.network.FriendlyByteBuf;
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
 * <p>The response travels as a name and an unknown one is refused at the decoder, not substituted.
 * The old policy clamped to {@code REFUSE}, which reads as harmless until you notice it turns "I could
 * not understand this packet" into "the player declined" — a decode failure that surrenders the
 * player's choice for them. A rejected packet leaves the encounter open, and the window's own timeout
 * is what produces a refusal.
 */
public record GuardChallengeResponseC2SPacket(UUID encounterId, ChallengeResponse response) {

    public GuardChallengeResponseC2SPacket {
        encounterId = encounterId == null ? new UUID(0L, 0L) : encounterId;
        response = response == null ? ChallengeResponse.REFUSE : response;
    }

    public static void encode(GuardChallengeResponseC2SPacket msg, FriendlyByteBuf buf) {
        buf.writeUUID(msg.encounterId);
        PacketBounds.writeEnum(buf, msg.response);
    }

    public static GuardChallengeResponseC2SPacket decode(FriendlyByteBuf buf) {
        UUID encounterId = buf.readUUID();
        ChallengeResponse response = PacketBounds.readEnum(buf, ChallengeResponse.class)
                .orElseThrow(() -> new DecoderException("Unknown challenge response"));
        return new GuardChallengeResponseC2SPacket(encounterId, response);
    }

    public static void handle(GuardChallengeResponseC2SPacket msg, Supplier<NetworkEvent.Context> ctx) {
        ServerPacketGuard.accept(ctx, RequestBudget.Category.CHALLENGE,
                sender -> GuardChallengeService.respond(sender, msg.encounterId(), msg.response()));
    }
}
