package dev.otectus.mcacrime.enforcement;

/**
 * How many guards a village should have, and how many to convert this pass.
 *
 * <p>Pure, with no game or config dependency, because the arithmetic is the whole feature and the
 * rounding rules are what an operator will actually check against. Ten villagers ask for one guard,
 * twenty ask for two, fifty ask for five — round up, floor at a minimum, and never manufacture a guard
 * for a village with nobody in it.
 *
 * <p><b>On hysteresis.</b> The requirement asks that a population wobbling by one cannot cause guards
 * to be created and removed repeatedly. That is impossible here by construction rather than by a
 * deadband: this pass only ever <em>adds</em>, so a population swing can add and never subtract, and
 * the guard count is monotonically non-decreasing whatever the population does. What remains is
 * redundant re-evaluation, and that is handled in the time domain by a per-village cooldown rather
 * than by a margin on the count — a deadband would be actively wrong, because with a minimum of one
 * and a village of ten, a deadband of one would refuse to create the first guard, which is precisely
 * the case the requirement calls out.
 */
public final class GuardPopulation {

    private GuardPopulation() {
    }

    /**
     * The number of guards a village of {@code population} should have.
     *
     * <p>{@code ceil(population * ratio)}, floored at {@code minimum}. An empty village returns zero:
     * the minimum is a floor on a real village, not a way to conjure a guard out of nobody.
     */
    public static int targetGuards(int population, double ratio, int minimum) {
        if (population <= 0) {
            return 0;
        }
        double safeRatio = Math.max(0.0, Math.min(1.0, ratio));
        int scaled = (int) Math.ceil(population * safeRatio);
        return Math.min(population, Math.max(Math.max(0, minimum), scaled));
    }

    /**
     * Guards assumed to exist among residents that are not currently loaded.
     *
     * <p>Not a nicety. MCA's own {@code spawnGuards} credits the unloaded remainder for exactly this
     * reason, and without it a village whose far half is out of render distance reads as short by half
     * its guards — so this pass would convert every loaded adult it could find, and the moment the rest
     * of the village loaded the village would be over target with no way to come back down.
     *
     * <p>Mirroring MCA's arithmetic means the two systems agree on the count rather than fighting over
     * it.
     */
    public static int assumedUnloadedGuards(int population, int loadedResidents, double ratio) {
        int unloaded = Math.max(0, population - Math.max(0, loadedResidents));
        double safeRatio = Math.max(0.0, Math.min(1.0, ratio));
        return (int) Math.ceil(unloaded * safeRatio);
    }

    /**
     * How many villagers to convert this pass: the shortfall against the target, capped by
     * {@code maxPerPass} and never negative.
     *
     * @param loadedGuards guards actually seen among the loaded residents, counted as the union of
     *                     this mod's responder selector and MCA's own guard test, so archers,
     *                     command-spawned guards and configured non-MCA responders all count
     */
    public static int conversionsNeeded(int population, int loadedResidents, int loadedGuards,
                                        double ratio, int minimum, int maxPerPass) {
        int target = targetGuards(population, ratio, minimum);
        int have = Math.max(0, loadedGuards) + assumedUnloadedGuards(population, loadedResidents, ratio);
        int shortfall = target - have;
        if (shortfall <= 0) {
            return 0;
        }
        return Math.min(shortfall, Math.max(0, maxPerPass));
    }
}
