package dev.otectus.mcacrime.lockpick;

import dev.otectus.mcacrime.McaCrime;
import net.minecraft.resources.ResourceLocation;

/**
 * What one session costs a lockpick, and which statistic that is (M3.3).
 *
 * <p>Pure, and it exists because the specification asks for one thing precisely: "record lockpicks
 * used/broken and successful picks accurately. A used pick is not automatically a broken pick." Those
 * are two different facts and they are counted under two different ids, so this is the arithmetic that
 * decides which one a given session produced.
 */
public final class PickWear {

    /** What happened to the pick. */
    public enum Outcome {
        /** The pick took a point of damage and survived. */
        USED,
        /** That point was its last. */
        BROKEN
    }

    /** The two statistic ids, named here so the pair can be compared without a registry. */
    public static final ResourceLocation SUCCESSFUL_LOCKPICKS = McaCrime.id("successful_lockpicks");
    public static final ResourceLocation LOCKPICKS_BROKEN = McaCrime.id("lockpicks_broken");

    private PickWear() {
    }

    /**
     * Whether spending one point finishes this pick.
     *
     * @param damageBefore the damage already on it
     * @param maxDamage    its total durability; zero or less means it cannot be damaged at all
     */
    public static Outcome wear(int damageBefore, int maxDamage) {
        if (maxDamage <= 0) {
            return Outcome.USED;
        }
        return damageBefore + 1 >= maxDamage ? Outcome.BROKEN : Outcome.USED;
    }

    /** The damage a pick carries after one session. Never past its maximum. */
    public static int damageAfter(int damageBefore, int maxDamage) {
        if (maxDamage <= 0) {
            return Math.max(0, damageBefore);
        }
        return Math.min(maxDamage, Math.max(0, damageBefore) + 1);
    }
}
