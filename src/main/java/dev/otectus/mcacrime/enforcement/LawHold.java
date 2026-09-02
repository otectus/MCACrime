package dev.otectus.mcacrime.enforcement;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Which responders are currently acting as law, and are therefore off-limits to the reaction system.
 *
 * <p>MCA's guards are villagers, so everything this mod does to villagers also applies to guards —
 * including {@code CrimeReactionService}, which puts a witness into a bounded panic/flee/report
 * controller. That was a problem the moment enforcement started actually working: the reaction ticker
 * runs on {@code reactionTickIntervalTicks} (5) and the guard scan on {@code guardScanIntervalTicks}
 * (10), and every reaction controller exit handed the villager back to MCA by clearing its target. The
 * arrest layer would set a guard on an offender and the reaction layer would clear it twice as fast as
 * it could be re-applied, so a guard alternated between charging and forgetting.
 *
 * <p>The fix is a hold rather than a blanket "guards do not react". A guard who is <em>not</em>
 * enforcing should still be able to flee a mugging or report what they saw — §11.2's resisting state is
 * explicitly not guards-only. What must not happen is a reaction overriding an arrest already in
 * progress, and that is exactly the window this covers.
 *
 * <p>Memory-only and deliberately so: an enforcement target is a fact about the current few seconds,
 * and a hold that survived a restart would describe an arrest nobody is making any more. Holds are
 * stamped with a deadline rather than cleared explicitly, so a scan that fails to run — because the
 * guard unloaded, died, or the player logged out — expires the hold instead of stranding it.
 */
public final class LawHold {

    private static final Map<UUID, Long> HELD = new ConcurrentHashMap<>();

    private LawHold() {
    }

    /** Marks a responder as enforcing until {@code untilGameTime}. Re-stamping extends the hold. */
    public static void hold(UUID responder, long untilGameTime) {
        if (responder != null) {
            HELD.merge(responder, untilGameTime, Math::max);
        }
    }

    /** Whether this responder is mid-enforcement right now. */
    public static boolean isHeld(UUID responder, long now) {
        Long until = responder == null ? null : HELD.get(responder);
        return until != null && until > now;
    }

    /** Releases a responder back to ordinary villager behaviour. */
    public static void clear(UUID responder) {
        if (responder != null) {
            HELD.remove(responder);
        }
    }

    /** Drops expired holds. Called from the guard scan, which is already throttled. */
    public static void prune(long now) {
        if (!HELD.isEmpty()) {
            HELD.values().removeIf(until -> until <= now);
        }
    }

    /** Drops every hold. Called on server stop, beside the other enforcement caches. */
    public static void clearAll() {
        HELD.clear();
    }

    /** Exposed for {@code /crime debug}: how many responders are enforcing right now. */
    public static int heldCount() {
        return HELD.size();
    }
}
