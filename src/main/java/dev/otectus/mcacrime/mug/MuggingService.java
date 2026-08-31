package dev.otectus.mcacrime.mug;

import dev.otectus.mcacrime.action.CrimeActionService;
import net.minecraft.server.level.ServerPlayer;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Mugging / robbery-over-murder (spec §8.6): a successful mug makes the villager "pay" (loot) for a moderate
 * theft-tier crime; if the villager is instead killed while being mugged, it is reclassified to the heavier
 * {@code mugging_murder} (no bonus loot, bigger Karma/Heat, bounty-eligible) so the default favors robbery
 * over murder. The "being mugged" marker is transient (non-persistent, swept) — same rationale as the harm
 * cooldown in {@code CrimeDetector}.
 */
public final class MuggingService {

    private record MugKey(UUID mugger, UUID victim) {
    }

    /** How long after a mug the victim's death still counts as a mugging-murder. */
    private static final long MUG_WINDOW_TICKS = 200L;

    private static final Map<MugKey, Long> RECENT = new ConcurrentHashMap<>();

    private MuggingService() {
    }

    /** Attempts to mug the villager the player is looking at within reach. Returns 1 on success, 0 on a refusal. */
    public static int mug(ServerPlayer player) {
        return CrimeActionService.startMugFromCommand(player);
    }

    /** Called at the overt threat point so a subsequent killing is still reclassified. */
    public static void markThreat(UUID mugger, UUID victim, long now) {
        sweep(now);
        RECENT.put(new MugKey(mugger, victim), now);
    }

    /**
     * True (consuming the marker) when {@code killer} was mid-mugging {@code victim} within the window — so
     * {@code CrimeDetector.onKill} reclassifies the death as {@code mugging_murder} (spec §8.6).
     */
    public static boolean wasMugging(UUID killer, UUID victim, long now) {
        Long stamp = RECENT.remove(new MugKey(killer, victim));
        return stamp != null && now - stamp < MUG_WINDOW_TICKS;
    }

    public static void onLogout(UUID player) {
        RECENT.keySet().removeIf(k -> k.mugger().equals(player));
    }

    private static void sweep(long now) {
        RECENT.entrySet().removeIf(entry -> now - entry.getValue() > MUG_WINDOW_TICKS * 4L);
    }

}
