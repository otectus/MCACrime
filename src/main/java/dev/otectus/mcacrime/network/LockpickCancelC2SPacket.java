package dev.otectus.mcacrime.network;

import dev.otectus.mcacrime.lockpick.LockpickService;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * "I closed the dial." One number, and it is only allowed to end the sender's own session (§3.5).
 *
 * <p>A cancel is not a failure: the meter is abandoned, the pick is not spent and no statistic moves.
 * Walking away from a lock costs nothing, which is the correct answer to a player who opened the wrong
 * screen.
 */
public record LockpickCancelC2SPacket(long sessionId) {

    public static void encode(LockpickCancelC2SPacket msg, FriendlyByteBuf buf) {
        buf.writeLong(msg.sessionId);
    }

    public static LockpickCancelC2SPacket decode(FriendlyByteBuf buf) {
        return new LockpickCancelC2SPacket(buf.readLong());
    }

    public static void handle(LockpickCancelC2SPacket msg, Supplier<NetworkEvent.Context> ctx) {
        ServerPacketGuard.accept(ctx, RequestBudget.Category.ACTION,
                sender -> LockpickService.cancel(sender, msg.sessionId));
    }
}
