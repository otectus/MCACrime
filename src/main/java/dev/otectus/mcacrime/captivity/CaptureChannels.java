package dev.otectus.mcacrime.captivity;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The transient registry of in-progress capture channels (spec §8.2), keyed by kidnapper UUID (one channel
 * per kidnapper). Non-persistent by design — exactly like the harm cooldown in {@code CrimeDetector}: a
 * channel must never survive a restart or logout. Driven by {@code CaptureTicker}.
 */
public final class CaptureChannels {

    private static final ConcurrentHashMap<UUID, CaptureChannel> ACTIVE = new ConcurrentHashMap<>();

    private CaptureChannels() {
    }

    public static void begin(CaptureChannel channel) {
        ACTIVE.put(channel.kidnapper, channel);
    }

    public static CaptureChannel get(UUID kidnapper) {
        return ACTIVE.get(kidnapper);
    }

    public static boolean has(UUID kidnapper) {
        return ACTIVE.containsKey(kidnapper);
    }

    public static void cancel(UUID kidnapper) {
        ACTIVE.remove(kidnapper);
    }

    public static List<UUID> kidnappers() {
        return new ArrayList<>(ACTIVE.keySet());
    }

    /** The channel break-on-hit hook: if {@code hurt} is mid-channel as a kidnapper, flag it broken. */
    public static void onKidnapperHurt(UUID hurt) {
        CaptureChannel channel = ACTIVE.get(hurt);
        if (channel != null) {
            channel.markBroken();
        }
    }

    /** Drops any channel where {@code uuid} is the kidnapper or the target (logout / death cleanup). */
    public static void clearFor(UUID uuid) {
        ACTIVE.remove(uuid);
        ACTIVE.values().removeIf(c -> c.target.equals(uuid));
    }
}
