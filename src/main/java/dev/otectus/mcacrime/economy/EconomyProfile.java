package dev.otectus.mcacrime.economy;

import dev.otectus.mcacrime.util.SafeMath;

import org.jetbrains.annotations.Nullable;
import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * How well-off a settlement is, as three fixed multipliers and nothing else (reference §12.2).
 *
 * <h2>Fixed profiles, not a derived economy</h2>
 *
 * <p>§12.2 is explicit about the shape: "start with fixed, documented profiles or small bounded
 * multipliers", and "a real village treasury, salaries, bribes, confiscation budgets, or
 * production-backed banking are separate projects". So this is a closed set of three, every
 * multiplier is a compile-time constant, and there is no state anywhere — a village's profile is
 * re-read from the settlement mod whenever it is asked for and is never persisted, because a stored
 * profile would be a second, drifting copy of a fact the settlement mod owns.
 *
 * <h2>The bound, and the one multiplier that is not symmetric</h2>
 *
 * <p>Every multiplier is clamped to {@code [0.5, 2.0]}: the worst a profile may do to a price is
 * halve or double it, so no settlement can make a crime free and none can make it ruinous.
 *
 * <p>{@link #scaleRefill} is the exception and deliberately so. §12.2's hard rule is that "refreshing
 * an API snapshot, switching a job, or changing the calendar must not mint currency", so a profile may
 * only ever select a refill <em>at or below</em> the amount the operator configured. The multiplier is
 * therefore applied and then floored against the configured value: a rich village's villagers carry
 * what the config says they carry, and a poor village's carry less. A profile that could raise the
 * refill would be exactly the minting §12.2 forbids, dressed as a setting.
 */
public enum EconomyProfile {

    /** A hamlet at the edge of things: little money, little law, little to steal. */
    FRONTIER("frontier", 0.75D, 0.75D, 0.90D, 0.60D,
            "a frontier settlement: smaller fines, smaller bounties, and thinner purses"),

    /** The default, and the one every village is until something says otherwise. */
    TOWN("town", 1.00D, 1.00D, 1.00D, 1.00D,
            "an ordinary town: every price is exactly what the configuration says"),

    /** A place with something worth guarding. */
    PROSPEROUS("prosperous", 1.35D, 1.35D, 1.15D, 1.00D,
            "a prosperous settlement: larger fines and bounties, dearer contraband");

    /** The narrowest a profile may make a price. */
    public static final double MIN_MULTIPLIER = 0.5D;

    /** The widest a profile may make a price. */
    public static final double MAX_MULTIPLIER = 2.0D;

    private final String id;
    private final double fineScale;
    private final double bountyScale;
    private final double fenceScale;
    private final double refillScale;
    private final String description;

    EconomyProfile(String id, double fineScale, double bountyScale, double fenceScale,
                   double refillScale, String description) {
        this.id = id;
        this.fineScale = clamp(fineScale);
        this.bountyScale = clamp(bountyScale);
        this.fenceScale = clamp(fenceScale);
        this.refillScale = clamp(refillScale);
        this.description = description;
    }

    private static double clamp(double value) {
        if (!Double.isFinite(value)) {
            return 1.0D;
        }
        return Math.max(MIN_MULTIPLIER, Math.min(MAX_MULTIPLIER, value));
    }

    public String id() {
        return id;
    }

    public String description() {
        return description;
    }

    /** What this settlement does to a fine, in {@code [0.5, 2.0]}. */
    public double fineScale() {
        return fineScale;
    }

    /** What this settlement does to a bounty, in {@code [0.5, 2.0]}. */
    public double bountyScale() {
        return bountyScale;
    }

    /** What this settlement does to a fence's base prices, in {@code [0.5, 2.0]}. */
    public double fenceScale() {
        return fenceScale;
    }

    /** What this settlement does to a villager's daily purse refill, in {@code [0.5, 2.0]}. */
    public double refillScale() {
        return refillScale;
    }

    /** Whether this profile changes anything at all. {@link #TOWN} does not, which is why it is default. */
    public boolean neutral() {
        return fineScale == 1.0D && bountyScale == 1.0D && fenceScale == 1.0D && refillScale == 1.0D;
    }

    // --- the four pure functions the rest of the mod plugs into ------------------------------------

    /** A fine component, scaled. Never negative, never overflowing. */
    public int scaleFine(int component) {
        return (int) Math.min(Integer.MAX_VALUE, scale(component, fineScale));
    }

    /** A bounty, scaled. */
    public long scaleBounty(long bounty) {
        return scale(bounty, bountyScale);
    }

    /** A fence's base price for one good, scaled. */
    public long scalePrice(long basePrice) {
        return scale(basePrice, fenceScale);
    }

    /**
     * A villager's daily purse refill, scaled — but never above what was configured.
     *
     * <p>The floor against the configured value is the §12.2 rule in code: a profile selects from
     * within the operator's ceiling and can never raise it, so no snapshot refresh, job change or
     * calendar roll can put a single emerald into the world that the configuration did not already
     * allow.
     */
    public int scaleRefill(int configuredIncome) {
        int configured = Math.max(0, configuredIncome);
        return Math.min(configured, (int) Math.min(Integer.MAX_VALUE, scale(configured, refillScale)));
    }

    private static long scale(long value, double multiplier) {
        return value <= 0L ? Math.max(0L, value) : Math.max(0L, SafeMath.mulSat(value, multiplier));
    }

    public static Optional<EconomyProfile> parse(@Nullable String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        String needle = raw.trim().toLowerCase(Locale.ROOT);
        return Arrays.stream(values())
                .filter(profile -> profile.id.equals(needle)
                        || profile.name().toLowerCase(Locale.ROOT).equals(needle))
                .findFirst();
    }

    public static String names() {
        return Arrays.stream(values()).map(EconomyProfile::id).collect(Collectors.joining(", "));
    }
}
