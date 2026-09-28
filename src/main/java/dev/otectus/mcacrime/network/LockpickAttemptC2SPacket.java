package dev.otectus.mcacrime.network;

import dev.otectus.mcacrime.lockpick.LockpickService;
import dev.otectus.mcacrime.lockpick.LockpickSession;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * One alignment attempt: "I am on phase N and the pick is at this angle" (§3.5, §1.6).
 *
 * <p>Three bounded numbers. No actor — identity is the connection. No target — the session was pinned
 * to one when the server opened it. No outcome, no durability, no block position. Every one of those
 * appears in upstream's equivalent and every one of them is an exploit there.
 *
 * <p>The phase is a claim the server checks rather than trusts: an attempt naming the phase that has
 * already been won is refused, which is what stops a replay of the winning angle from winning twice.
 *
 * @param sessionId          the session the client believes it is in
 * @param phase              the phase it believes it is on
 * @param angleMilliDegrees  where it says the pick is, wrapped into one turn on arrival
 */
public record LockpickAttemptC2SPacket(long sessionId, int phase, int angleMilliDegrees) {

    public LockpickAttemptC2SPacket {
        phase = Math.max(0, Math.min(PacketBounds.MAX_LOCKPICK_PHASE, phase));
        angleMilliDegrees = LockpickSession.wrap(angleMilliDegrees);
    }

    public static void encode(LockpickAttemptC2SPacket msg, FriendlyByteBuf buf) {
        buf.writeLong(msg.sessionId);
        buf.writeVarInt(msg.phase);
        buf.writeVarInt(msg.angleMilliDegrees);
    }

    public static LockpickAttemptC2SPacket decode(FriendlyByteBuf buf) {
        return new LockpickAttemptC2SPacket(buf.readLong(), buf.readVarInt(), buf.readVarInt());
    }

    public static void handle(LockpickAttemptC2SPacket msg, Supplier<NetworkEvent.Context> ctx) {
        ServerPacketGuard.accept(ctx, RequestBudget.Category.ACTION,
                sender -> LockpickService.attempt(sender, msg.sessionId, msg.phase, msg.angleMilliDegrees));
    }
}
