package dev.otectus.mcacrime.compat;

/**
 * One villager's Townstead needs, as plain numbers MCA: Crime owns.
 *
 * <h2>Why {@code tracked} is the first field</h2>
 *
 * <p>Every number here is zero when Townstead is absent, when {@code READ_NEEDS} did not bind, or
 * when the villager's life stage has needs switched off (Townstead pins hunger and thirst for a
 * stage whose {@code needs} flag is false — an egg, for instance). Zero hunger also happens to be
 * <em>starving</em>. So a caller comparing {@code hunger() <= EMERGENCY} would treat a village with
 * no Townstead at all as a village on the edge of death, and would do it silently.
 *
 * <p>{@code tracked} is the fix, and the reason the interpretation lives here rather than at the call
 * sites: {@link #starving()}, {@link #parched()} and {@link #exhausted()} all answer {@code false}
 * unless the reading is real. Nothing outside this record should compare a raw need against a
 * threshold.
 *
 * <p>{@code gated} is <b>not</b> "needs disabled" — it is Townstead's fatigue recovery gate: once a
 * villager collapses they stay gated until fatigue falls back under the recovery threshold, so they
 * cannot be shaken awake and put straight back to work. It is carried because custody and escort care
 * about it, not because it says anything about hunger.
 *
 * @param tracked whether these values are a real reading rather than the absent-Townstead default
 * @param hunger 0..{@link #HUNGER_MAX}
 * @param thirst 0..{@link #THIRST_MAX}
 * @param fatigue 0..{@link #FATIGUE_MAX}, collapsing at {@link #COLLAPSE_THRESHOLD}
 * @param collapsed whether Townstead has floored this villager
 * @param gated whether the fatigue recovery gate is still holding after a collapse
 */
public record TownsteadNeedsView(
        boolean tracked,
        int hunger,
        float saturation,
        float hungerExhaustion,
        int thirst,
        int quenched,
        float thirstExhaustion,
        int fatigue,
        boolean collapsed,
        boolean gated) {

    /** Townstead's own ranges ({@code HungerData}, {@code ThirstData}, {@code FatigueData}). */
    public static final int HUNGER_MAX = 100;
    public static final int THIRST_MAX = 20;
    public static final int QUENCHED_MAX = 20;
    public static final int FATIGUE_MAX = 20;

    /** Townstead's own thresholds, verified against the probe jar by {@code TownsteadBindingProbeTest}. */
    public static final int HUNGER_EMERGENCY = 25;
    public static final int THIRST_EMERGENCY = 4;
    public static final int FATIGUE_EXHAUSTED = 16;
    public static final int COLLAPSE_THRESHOLD = 20;

    private static final TownsteadNeedsView UNTRACKED =
            new TownsteadNeedsView(false, 0, 0f, 0f, 0, 0, 0f, 0, false, false);

    /**
     * The only correct value for "no reading": every number zero, and every derived judgement false.
     * Used when Townstead is absent, when the capability is unbound, and when the life stage has no
     * needs at all.
     */
    public static TownsteadNeedsView untracked() {
        return UNTRACKED;
    }

    /** Below Townstead's own emergency hunger threshold, and only when the reading is real. */
    public boolean starving() {
        return tracked && hunger <= HUNGER_EMERGENCY;
    }

    /** Below Townstead's own emergency thirst threshold, and only when the reading is real. */
    public boolean parched() {
        return tracked && thirst <= THIRST_EMERGENCY;
    }

    /** At or above Townstead's exhausted threshold, and only when the reading is real. */
    public boolean exhausted() {
        return tracked && fatigue >= FATIGUE_EXHAUSTED;
    }

    /**
     * Whether Townstead currently has this villager off their feet. True only for a real reading —
     * an unbound capability must never freeze a villager MCA: Crime is escorting.
     */
    public boolean incapacitated() {
        return tracked && collapsed;
    }

    /** Whether the fatigue recovery gate is still holding, so rest must not be interrupted yet. */
    public boolean restRequired() {
        return tracked && gated;
    }

    /** A short operator line; says "not tracked" rather than printing misleading zeroes. */
    public String describe() {
        if (!tracked) {
            return "needs: not tracked";
        }
        return "needs: hunger " + hunger + "/" + HUNGER_MAX
                + ", thirst " + thirst + "/" + THIRST_MAX
                + ", fatigue " + fatigue + "/" + FATIGUE_MAX
                + (collapsed ? ", collapsed" : "")
                + (gated ? ", recovery-gated" : "");
    }
}
