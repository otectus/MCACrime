package dev.otectus.mcacrime.compat;

import org.jetbrains.annotations.Nullable;

/**
 * Townstead's three need scales, normalised, and the one number MCA: Crime is willing to derive from
 * them.
 *
 * <p>Pure arithmetic with no bridge, no entity and no config: everything that could fail has already
 * happened by the time a {@link TownsteadNeedsView} exists, so this file only has to be right about
 * ranges. That separation is deliberate — the ranges are the part most likely to go stale when
 * Townstead changes, and keeping them here means one test pins them rather than a dozen call sites
 * quietly disagreeing.
 *
 * <h2>Where the ranges come from</h2>
 *
 * <p>Hunger is 0–100, thirst 0–20 and fatigue 0–20 with collapse at 20, read from Townstead's own
 * {@code hunger/HungerData}, {@code thirst/ThirstData} and {@code fatigue/FatigueData}. The bridge
 * exposes no constant for any of them — {@code TownsteadBridge.Ops} has queries and no scale
 * accessors — so they are carried as MCA: Crime's own constants on {@link TownsteadNeedsView} and
 * verified against the real jar by {@code TownsteadBindingProbeTest}. If a future Townstead publishes
 * them, read them there and delete the constants; until then this comment is the provenance.
 *
 * <h2>Why an untracked reading contributes nothing</h2>
 *
 * <p>Zero hunger is also "starving", so an absent Townstead handed to a threat model would make every
 * villager on every install behave like a starving one. {@link TownsteadNeedsView#tracked()} is the
 * distinction, and every method here returns a neutral value when it is false — never a zero that
 * happens to mean the worst possible state.
 */
public final class TownsteadNeeds {

    /**
     * The hardest this may push a threat factor, in either direction.
     *
     * <p>Small on purpose. Hunger is not a personality: a server owner has tuned bravery, panic and
     * compliance thresholds against MCA: Crime's own numbers, and a companion mod's needs may nudge
     * that and must not rewrite it. Ten percent is visible over a village and invisible in any single
     * encounter, which is the right size for a flavour input.
     */
    public static final double MAX_THREAT_ADJUSTMENT = 0.10D;

    /** Wellbeing at which the adjustment is zero: the state MCA: Crime's own numbers were tuned at. */
    private static final double NEUTRAL_WELLBEING = 0.5D;

    private TownsteadNeeds() {
    }

    /** How well fed, 0 (starving) to 1 (full). 1 when the reading is not real. */
    public static double hunger(@Nullable TownsteadNeedsView view) {
        return scale(view, view == null ? 0 : view.hunger(), TownsteadNeedsView.HUNGER_MAX);
    }

    /** How well watered, 0 (parched) to 1 (full). 1 when the reading is not real. */
    public static double thirst(@Nullable TownsteadNeedsView view) {
        return scale(view, view == null ? 0 : view.thirst(), TownsteadNeedsView.THIRST_MAX);
    }

    /**
     * How rested, 0 (collapsing) to 1 (fresh). 1 when the reading is not real.
     *
     * <p>Inverted relative to Townstead's own number, which counts upwards to collapse. Every other
     * value in this file reads "1 is fine", and a single scale that ran the other way would be read
     * wrongly exactly once and then stay wrong.
     */
    public static double rest(@Nullable TownsteadNeedsView view) {
        double fatigue = scale(view, view == null ? 0 : view.fatigue(), TownsteadNeedsView.FATIGUE_MAX);
        return view == null || !view.tracked() ? 1D : 1D - fatigue;
    }

    private static double scale(@Nullable TownsteadNeedsView view, int value, int max) {
        if (view == null || !view.tracked() || max <= 0) {
            return 1D;
        }
        return clamp((double) value / max);
    }

    /**
     * The three scales as one number, 0 (deprived on every axis) to 1 (comfortable on every axis).
     *
     * <p>A plain mean rather than a weighted one: MCA: Crime has no evidence that thirst matters more
     * than hunger to how somebody reacts to being robbed, and inventing weights would be dressing a
     * guess up as a model.
     */
    public static double wellbeing(@Nullable TownsteadNeedsView view) {
        if (view == null || !view.tracked()) {
            return NEUTRAL_WELLBEING;
        }
        return clamp((hunger(view) + thirst(view) + rest(view)) / 3D);
    }

    /**
     * What to add to a bravery-shaped threat factor for this villager, in
     * [-{@value #MAX_THREAT_ADJUSTMENT}, +{@value #MAX_THREAT_ADJUSTMENT}].
     *
     * <p>Zero for an untracked reading, and zero for a villager in the middle of the scale. Callers
     * must still check {@code townstead.needResponseModifiers} before applying it: this function has no
     * config access on purpose, so the switch is visible at the site that changes behaviour.
     */
    public static double threatAdjustment(@Nullable TownsteadNeedsView view) {
        if (view == null || !view.tracked()) {
            return 0D;
        }
        double centred = (wellbeing(view) - NEUTRAL_WELLBEING) / NEUTRAL_WELLBEING;
        return Math.max(-MAX_THREAT_ADJUSTMENT, Math.min(MAX_THREAT_ADJUSTMENT,
                centred * MAX_THREAT_ADJUSTMENT));
    }

    /**
     * Applies {@link #threatAdjustment} to a 0..1 factor, keeping it inside its own range.
     *
     * @param apply whether {@code townstead.needResponseModifiers} is on; false returns {@code factor}
     *              untouched, which is the default on every install
     */
    public static double adjustFactor(double factor, @Nullable TownsteadNeedsView view, boolean apply) {
        if (!apply || !Double.isFinite(factor)) {
            return factor;
        }
        return clamp(factor + threatAdjustment(view));
    }

    private static double clamp(double value) {
        return Math.max(0D, Math.min(1D, value));
    }
}
