package dev.otectus.mcacrime.enforcement;

import dev.otectus.mcacrime.activity.CrimeActivityRegistry;
import dev.otectus.mcacrime.activity.CrimeActivityView;
import net.minecraft.world.entity.Entity;

import javax.annotation.Nullable;
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
 * <p>Responders are excluded from civilian fear/compliance by role. The hold also protects temporary
 * law assignments and their navigation during conversations, pursuit and escorts.
 *
 * <p>Memory-only and deliberately so: an enforcement target is a fact about the current few seconds,
 * and a hold that survived a restart would describe an arrest nobody is making any more. Holds are
 * stamped with a deadline rather than cleared explicitly, so a scan that fails to run — because the
 * guard unloaded, died, or the player logged out — expires the hold instead of stranding it.
 */
public final class LawHold {

    private static final Map<UUID, Long> HELD = new ConcurrentHashMap<>();

    /**
     * The activity generation each hold took, so the release can be its own and nobody else's.
     *
     * <p>Only holds that actually won a claim appear here. A hold refused by a stronger claim — a
     * guard already escorting somebody, a villager already in custody — still stamps {@link #HELD},
     * because being off-limits to the reaction system is exactly as true then as it was before this
     * registry existed; it simply has no claim to give back.
     */
    private static final Map<UUID, Long> CLAIMS = new ConcurrentHashMap<>();

    /** The owner token every hold claims under, so a re-stamp renews instead of re-taking. */
    private static final String OWNER = "law_hold";

    private LawHold() {
    }

    /** Marks a responder as enforcing until {@code untilGameTime}. Re-stamping extends the hold. */
    public static void hold(UUID responder, long untilGameTime) {
        if (responder != null) {
            HELD.merge(responder, untilGameTime, Math::max);
        }
    }

    /**
     * The same hold, as an activity claim the rest of the world can see.
     *
     * <p>{@code LawHold} answers one question — "is law busy with this entity?" — for MCA: Crime's own
     * reaction ticker. The claim answers a wider one for everybody else, including the behaviour gates
     * that keep a villager from wandering off to a workbench mid-arrest. Both are stamped here so
     * there is one call at each site rather than two that can drift apart.
     *
     * <p>{@code kind} is what law is actually doing, because the yield table differs: a guard standing
     * still reciting charges may be interrupted by things a guard walking a prisoner to a cell may not.
     */
    public static void hold(@Nullable Entity responder, long untilGameTime,
                            CrimeActivityView.Kind kind) {
        if (responder == null) {
            return;
        }
        UUID id = responder.getUUID();
        hold(id, untilGameTime);
        try {
            long now = responder.level().getGameTime();
            int lease = (int) Math.max(1L, Math.min(Integer.MAX_VALUE, untilGameTime - now));
            long generation = CrimeActivityRegistry.claim(id,
                    responder.level().dimension().location(), kind, OWNER, now, lease);
            if (generation != CrimeActivityRegistry.REFUSED) {
                CLAIMS.put(id, generation);
            }
        } catch (Throwable t) {
            // The hold itself is already recorded. A registry that could break an arrest would be a
            // worse coordination layer than none.
            CLAIMS.remove(id);
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
            releaseClaim(responder);
        }
    }

    /** Drops expired holds. Called from the guard scan, which is already throttled. */
    public static void prune(long now) {
        if (!HELD.isEmpty()) {
            HELD.entrySet().removeIf(entry -> {
                if (entry.getValue() > now) {
                    return false;
                }
                releaseClaim(entry.getKey());
                return true;
            });
        }
    }

    private static void releaseClaim(UUID responder) {
        Long generation = CLAIMS.remove(responder);
        if (generation != null) {
            // Generation-scoped: a hold that ended after an escort or a custody transfer took the same
            // villager must not clear the claim that replaced it.
            CrimeActivityRegistry.release(responder, generation);
        }
    }

    /** Drops every hold. Called on server stop, beside the other enforcement caches. */
    public static void clearAll() {
        HELD.clear();
        CLAIMS.clear();
    }

    /** Exposed for {@code /crime debug}: how many responders are enforcing right now. */
    public static int heldCount() {
        return HELD.size();
    }
}
