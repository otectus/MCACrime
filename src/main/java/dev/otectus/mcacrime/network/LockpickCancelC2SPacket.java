package dev.otectus.mcacrime.network;

import dev.otectus.mcacrime.McaCrime;
import dev.otectus.mcacrime.lockpick.LockpickService;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * "I am putting the pick away" (§3.5).
 *
 * <p>One number, and it is only a hint: the session it names is validated against the sender before
 * anything is cancelled, so this can end the sender's own session and nobody else's. Everything that
 * ends a session without the player asking — death, logout, range, a swapped item, a replaced target
 * — is noticed by the server tick instead, because a client that has stopped talking cannot report
 * its own disconnection.
 */
public record LockpickCancelC2SPacket(long sessionId) implements CustomPacketPayload {

    public static final Type<LockpickCancelC2SPacket> TYPE = new Type<>(McaCrime.id("lockpick_cancel"));

    public static final StreamCodec<RegistryFriendlyByteBuf, LockpickCancelC2SPacket> STREAM_CODEC =
            StreamCodec.composite(
                    CrimeStreamCodecs.LONG, LockpickCancelC2SPacket::sessionId,
                    LockpickCancelC2SPacket::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(LockpickCancelC2SPacket msg, IPayloadContext ctx) {
        ServerPacketGuard.accept(ctx, RequestBudget.Category.ACTION,
                sender -> LockpickService.cancel(sender, msg.sessionId));
    }
}
