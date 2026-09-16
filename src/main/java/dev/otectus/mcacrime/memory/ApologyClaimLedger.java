package dev.otectus.mcacrime.memory;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Correlates the two Forge interaction paths one physical right-click can produce (0.7.2 §12.5).
 *
 * <p>Invariant 11 is that one interaction produces one action. When the empty-hand gesture claims a
 * click, the companion off-hand delivery of that same click has to be consumed silently instead of
 * being routed a second time. This records exactly one claim per player — who they claimed against
 * and on which game tick — and only recognises a companion path for that same target within the same
 * tick window.
 *
 * <p>It is a de-duplicator, not a permission: per-incident protection still lives in
 * {@link ApologyStatus} and the memory itself, so losing a token can at worst cost one extra route
 * decision, never an extra apology.
 */
public final class ApologyClaimLedger {

    /** One tick of slack: the companion path is delivered inside the same server tick. */
    static final long WINDOW_TICKS = 1L;

    private static final Map<UUID, Claim> CLAIMS = new ConcurrentHashMap<>();

    private ApologyClaimLedger() {
    }

    record Claim(UUID target, long tick) {
    }

    /** Records that this player's click against this villager has already been handled. */
    public static void claim(UUID player, UUID target, long tick) {
        CLAIMS.put(player, new Claim(target, tick));
    }

    /** Whether this event is the companion path of a click already claimed. */
    public static boolean isCompanionPath(UUID player, UUID target, long tick) {
        Claim claim = CLAIMS.get(player);
        if (claim == null || !claim.target().equals(target)) return false;
        long elapsed = tick - claim.tick();
        if (elapsed < 0 || elapsed > WINDOW_TICKS) {
            CLAIMS.remove(player, claim);
            return false;
        }
        return true;
    }

    /** Drops a player's claim on logout, so the map cannot outlive the session that made it. */
    public static void forget(UUID player) {
        CLAIMS.remove(player);
    }

    /** Drops every claim when the server stops. */
    public static void clear() {
        CLAIMS.clear();
    }
}
