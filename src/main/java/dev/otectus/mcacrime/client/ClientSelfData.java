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

    private ClientSelfData() {
    }

    public static void update(long karma, long heat, Band band, boolean wanted) {
        ClientSelfData.karma = karma;
        ClientSelfData.heat = heat;
        ClientSelfData.band = band;
        ClientSelfData.wanted = wanted;
    }

    public static void clear() {
        karma = 0L;
        heat = 0L;
        band = Band.GREY;
        wanted = false;
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
}
