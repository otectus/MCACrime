package dev.otectus.mcacrime.enforcement;

import dev.otectus.mcacrime.McaCrime;
import dev.otectus.mcacrime.McaCrimeConfig;
import dev.otectus.mcacrime.compat.McaCompat;
import dev.otectus.mcacrime.compat.mca.McaHandles;
import dev.otectus.mcacrime.detect.EntitySelectors;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Keeps villages topped up to the configured share of guards.
 *
 * <p><b>Off by default, and the reason matters.</b> MCA Reborn already does this: its
 * {@code VillageGuardsManager} targets {@code ceil(population * guardSpawnFraction)} with a default
 * fraction of 0.175, which is higher than the 0.10 default here. With both running, MCA reaches its
 * target first and this pass finds nothing to do. It exists for servers that have turned MCA's
 * fraction down, or want a floor MCA's fraction does not provide.
 *
 * <p>The pass only ever adds. Nothing here converts a guard back into a villager, which is what makes
 * a population that wobbles by one harmless: the guard count can only rise, so there is no state to
 * oscillate. Redundant work is bounded in the time domain instead, by a per-village cooldown.
 *
 * <p><b>Cost.</b> One dimension and one village per pass, only in dimensions that have players in
 * them, on a scan that already runs throttled. Nothing is remembered about what was converted — the
 * count is re-derived from the live world every time — which is also the answer to duplicates across a
 * chunk reload: there is no record to go stale.
 */
public final class GuardPopulationService {

    /**
     * Per-village cooldowns, keyed by dimension and MCA village id.
     *
     * <p>Memory-only on purpose. A restart costs each village one extra evaluation, and since the pass
     * counts existing guards from the live world and never converts down, that evaluation is
     * idempotent.
     */
    private static final Map<String, Long> COOLDOWNS = new ConcurrentHashMap<>();

    private static int counter;
    private static int levelCursor;

    private GuardPopulationService() {
    }

    /** One throttled pass. Called from the enforcement scan, which already holds the server. */
    public static void tick(MinecraftServer server) {
        if (server == null || !McaCrimeConfig.COMMON.manageGuardPopulation.get()) {
            return;
        }
        int interval = Math.max(1, McaCrimeConfig.COMMON.guardPopulationScanIntervalTicks.get());
        int scan = Math.max(1, McaCrimeConfig.COMMON.guardScanIntervalTicks.get());
        // The enforcement scan already divided the tick rate; divide again rather than counting raw
        // ticks, so changing either interval keeps the intended real-time cadence.
        if (++counter * scan < interval) {
            return;
        }
        counter = 0;
        if (!McaHandles.populationAvailable()) {
            return;
        }
        try {
            ServerLevel level = nextInhabitedLevel(server);
            if (level != null) {
                sweep(level);
            }
        } catch (Throwable t) {
            McaCrime.LOGGER.debug("MCA: Crime guard-population pass failed; continuing", t);
        }
    }

    /**
     * The next dimension with players in it, round-robin.
     *
     * <p>No player, no scan. That is what bounds the cost on a large save: an idle server with a
     * thousand villages across five dimensions does no work at all.
     */
    private static ServerLevel nextInhabitedLevel(MinecraftServer server) {
        List<ServerLevel> inhabited = new ArrayList<>();
        for (ServerLevel level : server.getAllLevels()) {
            if (!level.players().isEmpty()) {
                inhabited.add(level);
            }
        }
        if (inhabited.isEmpty()) {
            return null;
        }
        levelCursor = (levelCursor + 1) % inhabited.size();
        return inhabited.get(levelCursor);
    }

    /** Examines the first village in this level that is off cooldown, and stops. */
    private static void sweep(ServerLevel level) {
        long now = level.getGameTime();
        long cooldown = McaCrimeConfig.COMMON.guardPopulationCooldownTicks.get();
        for (Object village : McaHandles.villagesIn(level)) {
            if (!McaHandles.isRealVillage(village)) {
                continue;
            }
            String key = keyOf(level, village);
            Long until = COOLDOWNS.get(key);
            if (until != null && now < until) {
                continue;
            }
            COOLDOWNS.put(key, now + cooldown);
            topUp(level, village);
            return; // one village per pass; the rest wait their turn
        }
    }

    /** Counts what a village has, and converts the shortfall. */
    private static void topUp(ServerLevel level, Object village) {
        int population = McaHandles.villagePopulation(village);
        if (population <= 0) {
            return;
        }
        List<Object> residents = McaHandles.villageResidents(village, level);
        int loadedGuards = 0;
        List<Entity> candidates = new ArrayList<>();
        for (Object resident : residents) {
            if (!(resident instanceof Entity entity) || !entity.isAlive()) {
                continue;
            }
            // The union, deliberately. isResponder covers this mod's own notion of law, including
            // anything an operator added through responderEntities; isMcaGuard covers MCA's, which
            // includes its archers. Counting only one of the two would have the pass convert past a
            // target MCA had already met.
            boolean isLaw = (entity instanceof LivingEntity living && EntitySelectors.isResponder(living))
                    || McaHandles.isMcaGuard(entity);
            if (isLaw) {
                loadedGuards++;
                continue;
            }
            if (McaCompat.isAdultVillager(entity) && !McaHandles.isProfessionImportant(entity)) {
                candidates.add(entity);
            }
        }

        int needed = GuardPopulation.conversionsNeeded(population, residents.size(), loadedGuards,
                McaCrimeConfig.COMMON.guardPopulationRatio.get(),
                McaCrimeConfig.COMMON.guardPopulationMinimum.get(),
                McaCrimeConfig.COMMON.guardPopulationMaxPerPass.get());
        if (needed <= 0 || candidates.isEmpty()) {
            return;
        }
        // Random rather than nearest, so repeated passes do not always pick on whoever happens to stand
        // closest to the village centre.
        for (int i = 0; i < needed && !candidates.isEmpty(); i++) {
            Entity chosen = candidates.remove(level.random.nextInt(candidates.size()));
            if (McaCompat.makeGuard(chosen)) {
                McaCrime.LOGGER.debug("MCA: Crime promoted a villager to guard in {}", keyOf(level, village));
            }
        }
    }

    private static String keyOf(ServerLevel level, Object village) {
        return level.dimension().location() + "/" + McaHandles.villageIdOf(village);
    }

    /**
     * A read-only report for {@code /crime debug guards}.
     *
     * <p>Without this the only signal that the feature works is villagers slowly changing clothes.
     */
    public static List<String> report(ServerLevel level) {
        List<String> lines = new ArrayList<>();
        if (!McaCrimeConfig.COMMON.manageGuardPopulation.get()) {
            lines.add("manageGuardPopulation is off.");
        }
        if (!McaHandles.populationAvailable()) {
            lines.add("MCA village bindings are unavailable; the pass cannot run.");
            return lines;
        }
        double ratio = McaCrimeConfig.COMMON.guardPopulationRatio.get();
        int minimum = McaCrimeConfig.COMMON.guardPopulationMinimum.get();
        Set<String> seen = new LinkedHashSet<>();
        for (Object village : McaHandles.villagesIn(level)) {
            if (!McaHandles.isRealVillage(village) || !seen.add(keyOf(level, village))) {
                continue;
            }
            int population = McaHandles.villagePopulation(village);
            List<Object> residents = McaHandles.villageResidents(village, level);
            int guards = 0;
            for (Object resident : residents) {
                if (resident instanceof LivingEntity living && EntitySelectors.isResponder(living)) {
                    guards++;
                } else if (McaHandles.isMcaGuard(resident)) {
                    guards++;
                }
            }
            lines.add(String.format("%s: population %d, loaded %d, guards %d, target %d, needed %d",
                    keyOf(level, village), population, residents.size(), guards,
                    GuardPopulation.targetGuards(population, ratio, minimum),
                    GuardPopulation.conversionsNeeded(population, residents.size(), guards, ratio, minimum,
                            McaCrimeConfig.COMMON.guardPopulationMaxPerPass.get())));
        }
        if (lines.isEmpty()) {
            lines.add("No MCA villages in this dimension.");
        }
        return lines;
    }

    /** Drops every cooldown. Called on server stop. */
    public static void clearAll() {
        COOLDOWNS.clear();
        counter = 0;
        levelCursor = 0;
    }
}
