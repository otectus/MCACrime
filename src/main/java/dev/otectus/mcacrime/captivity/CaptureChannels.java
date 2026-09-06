package dev.otectus.mcacrime.captivity;

import dev.otectus.mcacrime.action.ActionSession;
import dev.otectus.mcacrime.action.ActionSessionManager;
import dev.otectus.mcacrime.action.CancelReason;

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

    public static synchronized boolean beginIfFree(CaptureChannel channel) {
        if (ACTIVE.containsKey(channel.kidnapper)
                || ACTIVE.values().stream().anyMatch(active -> active.target.equals(channel.target))) {
            return false;
        }
        ACTIVE.put(channel.kidnapper, channel);
        return true;
    }

    public static CaptureChannel get(UUID kidnapper) {
        return ACTIVE.get(kidnapper);
    }

    public static boolean has(UUID kidnapper) {
        return ACTIVE.containsKey(kidnapper);
    }

    public static boolean targets(UUID target) {
        return ACTIVE.values().stream().anyMatch(channel -> channel.target.equals(target));
    }

    public static void cancel(UUID kidnapper) {
        release(ACTIVE.remove(kidnapper), CancelReason.CONFLICT);
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

    /**
     * Drops any channel where {@code uuid} is the kidnapper or the target (logout / death / dimension
     * change cleanup).
     *
     * <p>Both roles, because a channel is just as dead when it is the victim who walked through the
     * portal. Each dropped channel releases its session lease on the way out — without that, a lock
     * taken by a capture would outlive the capture and refuse every later action against either party.
     */
    public static void clearFor(UUID uuid) {
        release(ACTIVE.remove(uuid), CancelReason.ACTOR_GONE);
        for (CaptureChannel channel : new ArrayList<>(ACTIVE.values())) {
            if (channel.target.equals(uuid) && ACTIVE.remove(channel.kidnapper, channel)) {
                release(channel, CancelReason.TARGET_GONE);
            }
        }
    }

    /**
     * Ends the session a channel was holding, if it was still holding one.
     *
     * <p>A no-op for a session something else already settled — the commit that just finished, a
     * damage event, the action ticker — because {@code cancel} is compare-and-transition and only the
     * first caller does any work.
     */
    private static void release(@javax.annotation.Nullable CaptureChannel channel, CancelReason reason) {
        if (channel == null) {
            return;
        }
        ActionSession session = channel.session();
        if (session != null) {
            ActionSessionManager.cancel(session, reason);
        }
    }
}
