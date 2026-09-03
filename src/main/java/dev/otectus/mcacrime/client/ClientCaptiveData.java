package dev.otectus.mcacrime.client;

/**
 * Client-side cache of the local player's captivity status (spec §10.3), fed by {@code CaptiveStatusS2CPacket}
 * and read by the reputation player card. Display-only; never classloaded on a dedicated server.
 */
public final class ClientCaptiveData {

    private static volatile boolean captive;
    private static volatile boolean lawful;
    private static volatile String captor = "";
    private static volatile long capRemainingTicks;

    private ClientCaptiveData() {
    }

    public static void update(boolean captive, boolean lawful, String captor, long capRemainingTicks) {
        ClientCaptiveData.captive = captive;
        ClientCaptiveData.lawful = lawful;
        ClientCaptiveData.captor = captor;
        ClientCaptiveData.capRemainingTicks = capRemainingTicks;
    }

    public static void clear() {
        captive = false;
        lawful = false;
        captor = "";
        capRemainingTicks = 0L;
    }

    public static boolean captive() {
        return captive;
    }

    public static boolean lawful() {
        return lawful;
    }

    public static String captor() {
        return captor;
    }

    public static long capRemainingTicks() {
        return capRemainingTicks;
    }
}
