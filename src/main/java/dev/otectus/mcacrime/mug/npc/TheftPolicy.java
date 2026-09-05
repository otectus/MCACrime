package dev.otectus.mcacrime.mug.npc;

import dev.otectus.mcacrime.McaCrimeConfig;

/**
 * What a thief is allowed to take, snapshotted from {@code criminalJobs.thief} (spec §"Theft
 * resolution"/§"Inventory theft eligibility").
 *
 * <p>A snapshot rather than live config reads for the same reason {@code ThiefPolicy} is one: the
 * plan and the commit that follows it must agree, and a config reload landing between the two would
 * otherwise let a thief plan against one rulebook and steal by another.
 */
public record TheftPolicy(long minCurrencySteal, long maxCurrencySteal, boolean stealAllIfBelowMinimum,
                          boolean protectHotbar, boolean protectArmor, boolean protectOffhand,
                          ItemTheftMode mode) {

    /**
     * How much of a stack one mugging takes.
     *
     * <p>{@link #SINGLE_ITEM} is the default because it is the only mode whose punishment does not
     * depend on how tidily the victim stacked their inventory: losing one iron ingot is the same loss
     * whether the slot held two or sixty-four.
     */
    public enum ItemTheftMode {
        /** One item count from the chosen slot. */
        SINGLE_ITEM,
        /** Everything in the chosen slot. */
        WHOLE_STACK,
        /** Somewhere between one and the whole slot. */
        RANDOM_COUNT
    }

    public TheftPolicy {
        minCurrencySteal = Math.max(0L, minCurrencySteal);
        maxCurrencySteal = Math.max(0L, maxCurrencySteal);
        if (minCurrencySteal > maxCurrencySteal) {
            // Validation reports this; the planner still has to do something sane with it.
            minCurrencySteal = maxCurrencySteal;
        }
        mode = mode == null ? ItemTheftMode.SINGLE_ITEM : mode;
    }

    public static TheftPolicy fromConfig() {
        McaCrimeConfig.Common c = McaCrimeConfig.COMMON;
        return new TheftPolicy(
                c.thiefMinCurrencySteal.get(),
                c.thiefMaxCurrencySteal.get(),
                c.thiefStealAllIfBelowMinimum.get(),
                c.thiefProtectHotbar.get(),
                c.thiefProtectArmor.get(),
                c.thiefProtectOffhand.get(),
                c.thiefItemTheftMode.get());
    }
}
