package dev.otectus.mcacrime.client;

import dev.otectus.mcacrime.crime.Band;

/**
 * Client-side cache of the local player's own crime status, fed by {@code SelfStatusS2CPacket} and read
 * by the reputation player card. Display-only. Client-only — never classloaded on a dedicated server.
 */
public final class ClientSelfData {

    private static volatile long karma;
    private static volatile long heat;
    private static volatile Band band = Band.GREY;
    private static volatile boolean wanted;
    private static volatile long jailRemainingTicks;
    private static volatile boolean legalTarget;

    private ClientSelfData() {
    }

    public static void update(long karma, long heat, Band band, boolean wanted, long jailRemainingTicks, boolean legalTarget) {
        ClientSelfData.karma = karma;
        ClientSelfData.heat = heat;
        ClientSelfData.band = band;
        ClientSelfData.wanted = wanted;
        ClientSelfData.jailRemainingTicks = jailRemainingTicks;
        ClientSelfData.legalTarget = legalTarget;
    }

    public static void clear() {
        karma = 0L;
        heat = 0L;
        band = Band.GREY;
        wanted = false;
        jailRemainingTicks = 0L;
        legalTarget = false;
    }

    public static long karma() {
        return karma;
    }

    public static long heat() {
        return heat;
    }

    public static Band band() {
        return band;
    }

    public static boolean wanted() {
        return wanted;
    }

    public static long jailRemainingTicks() {
        return jailRemainingTicks;
    }

    /**
     * Runs the local sentence countdown one client tick. Stops at zero and never goes negative.
     *
     * <p>The same shape as {@code ClientChallengeData.tick}, and for the same reason: the server sends
     * a tick count, not a deadline, so a client with a skewed clock or one that joined mid-sentence
     * still sees a counter that runs down at one tick per tick. The server resyncs on its own cadence
     * and is the only authority on when the sentence actually ends — a client whose counter reaches
     * zero early is not released early, and one whose counter lags is not held longer.
     */
    public static void tick() {
        if (jailRemainingTicks > 0L) {
            jailRemainingTicks--;
        }
    }

    public static boolean legalTarget() {
        return legalTarget;
    }
}
