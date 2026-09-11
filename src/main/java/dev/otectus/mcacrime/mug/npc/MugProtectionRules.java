package dev.otectus.mcacrime.mug.npc;

/**
 * When a player may be robbed again, decided as a pure function (0.7.0).
 *
 * <p>The complaint this answers is that NPC muggings came too often. Every cooldown the mod had was
 * scoped to the thief — its own record, its own last-mug stamp — so four thieves could rob one player
 * in four minutes with each of them well inside its limit. The three rules here belong to the
 * <em>victim</em> instead, and are therefore shared by every thief in the world: a grace window after
 * any mugging at all, a longer cooldown on the particular pair, and a daily cap.
 *
 * <p>Pure and total: no config, no capability, no level. {@link MugProtection} reads the values and
 * {@code MugTargetSelector} consumes the answer, which is what makes all three testable without a
 * server. Ticks are world game ticks, the same clock the thief's own cooldown is stamped against.
 */
public final class MugProtectionRules {

    /** Ticks in one Minecraft day, which is the window the daily cap counts over. */
    public static final long TICKS_PER_DAY = 24000L;

    private MugProtectionRules() {
    }

    /**
     * The new protection expiry after a grant. Never shortens an existing one: a player who has just
     * logged in during a long post-mugging window keeps the long window, because the shorter login
     * grace is a floor and not a replacement.
     *
     * @param multiplier {@code muggingFrequencyMultiplier}; above 1 makes muggings more frequent, so it
     *                   divides the protection rather than multiplying it
     */
    public static long grant(long existing, long now, int ticks, double multiplier) {
        long scaled = scale(ticks, multiplier);
        if (scaled <= 0L) {
            return existing;
        }
        return Math.max(existing, now + scaled);
    }

    /**
     * A cooldown length with the frequency multiplier applied. The multiplier says how often muggings
     * should happen, so a multiplier of 2 halves every window; 0 or less is treated as 1 rather than as
     * an infinite cooldown, because {@code defineInRange} already keeps it above 0.1.
     */
    public static long scale(int ticks, double multiplier) {
        if (ticks <= 0) {
            return 0L;
        }
        double factor = multiplier <= 0.0D ? 1.0D : multiplier;
        return Math.max(0L, Math.round(ticks / factor));
    }

    /**
     * Whether this thief may rob this player now. All three gates are hard: no score, no wealth and no
     * proximity trades any of them away.
     *
     * @param dailyCap {@code maxMuggingsPerPlayerPerDay}; 0 disables the cap entirely
     */
    public static boolean eligible(long protectedUntil, long pairCooldownUntil, int muggingsToday,
                                   int dailyCap, long now) {
        if (now < protectedUntil || now < pairCooldownUntil) {
            return false;
        }
        return dailyCap <= 0 || muggingsToday < dailyCap;
    }

    /** The world day a tick falls in, which is what the daily cap rolls over on. */
    public static long day(long gameTime) {
        return Math.floorDiv(gameTime, TICKS_PER_DAY);
    }
}
