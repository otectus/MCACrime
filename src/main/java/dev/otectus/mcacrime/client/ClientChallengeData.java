package dev.otectus.mcacrime.client;

import dev.otectus.mcacrime.network.GuardChallengeS2CPacket;

import org.jetbrains.annotations.Nullable;

/**
 * Client-side cache of the open guard challenge, if any. Display-only, and never classloaded on a
 * dedicated server.
 *
 * <p>The countdown is decremented locally from the tick count the server sent rather than recomputed
 * from a deadline. A client with a skewed clock, or one that joined mid-challenge, still sees a
 * counter that runs down at one tick per tick — and if it drifts, the authority on whether the window
 * actually closed is the server, which sends the closure explicitly.
 */
public final class ClientChallengeData {

    @Nullable
    private static volatile GuardChallengeS2CPacket current;
    private static volatile long remainingTicks;

    private ClientChallengeData() {
    }

    public static void update(GuardChallengeS2CPacket packet) {
        if (packet == null || !packet.open()) {
            clear();
            return;
        }
        current = packet;
        remainingTicks = packet.remainingTicks();
    }

    public static void clear() {
        current = null;
        remainingTicks = 0L;
    }

    @Nullable
    public static GuardChallengeS2CPacket current() {
        return current;
    }

    public static boolean active() {
        return current != null;
    }

    public static long remainingTicks() {
        return remainingTicks;
    }

    /** Runs the local countdown one client tick. Stops at zero and never goes negative. */
    public static void tick() {
        if (current != null && remainingTicks > 0L) {
            remainingTicks--;
        }
    }
}
