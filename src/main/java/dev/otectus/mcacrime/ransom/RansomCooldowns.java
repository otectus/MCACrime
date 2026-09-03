package dev.otectus.mcacrime.ransom;

/**
 * Pure ransom anti-farm cooldown math (spec §8.5): a fresh demand is blocked while any of the per-victim,
 * per-family, or per-village windows is still open. Game-time stamps in; no game deps, so it is unit-testable.
 */
public final class RansomCooldowns {

    private RansomCooldowns() {
    }

    /** True when {@code now} is still within {@code window} ticks of {@code lastStamp} (and the window is active). */
    public static boolean onCooldown(long lastStamp, long now, long window) {
        return window > 0L && lastStamp > 0L && now - lastStamp < window;
    }

    /** True when none of the three windows is open — a new demand is allowed. */
    public static boolean ready(long now,
                                long lastVictim, long victimWindow,
                                long lastFamily, long familyWindow,
                                long lastVillage, long villageWindow) {
        return !onCooldown(lastVictim, now, victimWindow)
                && !onCooldown(lastFamily, now, familyWindow)
                && !onCooldown(lastVillage, now, villageWindow);
    }
}
