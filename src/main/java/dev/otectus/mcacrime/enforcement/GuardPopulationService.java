package dev.otectus.mcacrime.enforcement;

import dev.otectus.mcacrime.McaCrime;
import dev.otectus.mcacrime.McaCrimeConfig;
import dev.otectus.mcacrime.compat.McaCompat;
import dev.otectus.mcacrime.compat.TownsteadRolePolicy;
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
        Assessment assessment = assess(level, village);
        if (assessment == null) {
            return;
        }
        RecruitmentReport report = assessment.report();
        if (report.halted()) {
            // Not a warning every cooldown: a village whose roles cannot be read stays that way, and a
            // repeated warning would train an operator to ignore it. The reason is in
            // /crime debug guards, where somebody looking for the shortfall will find it.
            McaCrime.LOGGER.debug("MCA: Crime is not drafting guards in {}: {}",
                    report.village(), report.reasons());
            return;
        }
        int needed = report.conversions();
        List<Entity> candidates = new ArrayList<>(assessment.candidates());
        if (needed <= 0 || candidates.isEmpty()) {
            return;
        }
        dev.otectus.mcacrime.job.WorldCriminalJobService jobs =
                dev.otectus.mcacrime.job.WorldCriminalJobService.of(level.getServer());
        // Random rather than nearest, so repeated passes do not always pick on whoever happens to stand
        // closest to the village centre.
        for (int i = 0; i < needed && !candidates.isEmpty(); i++) {
            Entity chosen = candidates.remove(level.random.nextInt(candidates.size()));
            // Re-read immediately before the promotion, not only at selection: the selection list is
            // built once per pass and a job can be assigned by the sweep, a command or a listener in
            // between.
            if (dev.otectus.mcacrime.job.NpcMuggerEligibility.guardPromotionBlocked(
                    jobs.isCriminal(chosen.getUUID()))) {
                i--; // this one did not count towards the shortfall
                continue;
            }
            // The same re-read for the settlement side, and for the same reason: a villager can be put
            // on shift between the scan and the promotion, and a workshop lost that way is not
            // recoverable by the next pass.
            if (TownsteadRolePolicy.of(chosen).protectedWorker()) {
                i--;
                continue;
            }
            if (McaCompat.makeGuard(chosen)) {
                McaCrime.LOGGER.debug("MCA: Crime promoted a villager to guard in {}", keyOf(level, village));
            }
        }
    }

    /** The candidate list a pass would draw from, alongside the report that explains it. */
    private record Assessment(RecruitmentReport report, List<Entity> candidates) {
    }

    /**
     * Counts one village, and decides whether it may be recruited from.
     *
     * <p>Shared by the pass and by {@code /crime debug guards} on purpose: the number an operator reads
     * has to be the number the pass acted on, and the previous split — a counting loop here and a
     * slightly different one in the report — is exactly how those two drift apart.
     */
    private static Assessment assess(ServerLevel level, Object village) {
        int population = McaHandles.villagePopulation(village);
        if (population <= 0) {
            return null;
        }
        dev.otectus.mcacrime.job.WorldCriminalJobService jobs =
                dev.otectus.mcacrime.job.WorldCriminalJobService.of(level.getServer());
        List<Object> residents = McaHandles.villageResidents(village, level);
        int loadedGuards = 0;
        int roster = 0;
        int protectedWorkers = 0;
        int unknownRoles = 0;
        List<Entity> candidates = new ArrayList<>();
        Set<String> reasons = new LinkedHashSet<>();
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
            // The other half of the role invariant (0.7.2): excluding law from crime is worthless if
            // this pass can turn the village thief into a guard on the next cooldown.
            if (!McaCompat.isAdultVillager(entity) || McaHandles.isProfessionImportant(entity)
                    || dev.otectus.mcacrime.job.NpcMuggerEligibility.guardPromotionBlocked(
                            jobs.isCriminal(entity.getUUID()))) {
                continue;
            }
            roster++;
            TownsteadRolePolicy.Role role = TownsteadRolePolicy.of(entity);
            if (role.protectedWorker()) {
                protectedWorkers++;
                reasons.add("a villager " + role.reason());
            } else if (!role.roleKnown()) {
                unknownRoles++;
                reasons.add("role unreadable: " + role.reason());
            } else {
                candidates.add(entity);
            }
        }

        double ratio = McaCrimeConfig.COMMON.guardPopulationRatio.get();
        int minimum = McaCrimeConfig.COMMON.guardPopulationMinimum.get();
        int shortfall = GuardPopulation.conversionsNeeded(population, residents.size(), loadedGuards,
                ratio, minimum, McaCrimeConfig.COMMON.guardPopulationMaxPerPass.get());
        // Halted, not merely short: one villager whose role cannot be read is enough to stop the whole
        // village, because the pass picks at random and the unreadable one might be the baker.
        boolean halted = unknownRoles > 0;
        RecruitmentReport report = new RecruitmentReport(keyOf(level, village), population,
                residents.size(), loadedGuards, GuardPopulation.targetGuards(population, ratio, minimum),
                shortfall, roster, candidates.size(), protectedWorkers, unknownRoles, halted,
                List.copyOf(reasons));
        return new Assessment(report, candidates);
    }

    private static String keyOf(ServerLevel level, Object village) {
        return level.dimension().location() + "/" + McaHandles.villageIdOf(village);
    }

    /**
     * A read-only report for {@code /crime debug guards}.
     *
     * <p>Without this the only signal that the feature works is villagers slowly changing clothes —
     * and with a settlement mod installed, the more common signal is villagers <em>not</em> changing
     * clothes, which looks identical to a broken feature. {@link #reports(ServerLevel)} is what makes
     * the difference legible.
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
        for (RecruitmentReport report : reports(level)) {
            lines.add(report.describe());
        }
        if (lines.isEmpty()) {
            lines.add("No MCA villages in this dimension.");
        }
        return lines;
    }

    /**
     * One assessment per village in this dimension, as the pass itself would make it.
     *
     * <p>Exposed rather than folded into {@link #report(ServerLevel)} because the duty side of the
     * enforcement work needs the numbers rather than the sentences, and because a caller that wants
     * both must not get two different answers.
     */
    public static List<RecruitmentReport> reports(ServerLevel level) {
        List<RecruitmentReport> reports = new ArrayList<>();
        if (level == null || !McaHandles.populationAvailable()) {
            return reports;
        }
        Set<String> seen = new LinkedHashSet<>();
        for (Object village : McaHandles.villagesIn(level)) {
            if (!McaHandles.isRealVillage(village) || !seen.add(keyOf(level, village))) {
                continue;
            }
            Assessment assessment = assess(level, village);
            if (assessment != null) {
                reports.add(assessment.report());
            }
        }
        return reports;
    }

    /** Drops every cooldown. Called on server stop. */
    public static void clearAll() {
        COOLDOWNS.clear();
        counter = 0;
        levelCursor = 0;
    }
}
