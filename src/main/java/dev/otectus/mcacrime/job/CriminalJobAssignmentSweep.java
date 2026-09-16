package dev.otectus.mcacrime.job;

import dev.otectus.mcacrime.McaCrime;
import dev.otectus.mcacrime.McaCrimeConfig;
import dev.otectus.mcacrime.captivity.CustodyRegistry;
import dev.otectus.mcacrime.compat.McaCompat;
import dev.otectus.mcacrime.compat.mca.McaHandles;
import dev.otectus.mcacrime.detect.CrimeCommunityResolver;
import dev.otectus.mcacrime.api.model.CrimeCommunityKey;
import dev.otectus.mcacrime.job.CriminalJobAssigner.AssignmentPolicy;
import dev.otectus.mcacrime.job.CriminalJobAssigner.Candidate;
import dev.otectus.mcacrime.state.world.CrimeWorldData;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.server.ServerLifecycleHooks;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;

/**
 * The pass that decides a village has a thief in it (0.5.1, spec §"Assignment and spawning").
 *
 * <p>Throttled to {@code criminalJobs.assignmentScanIntervalTicks} and bounded to the neighbourhood of
 * players, for the same reason the guard-population pass is: an idle server with a thousand villages
 * should do no work at all. Every villager it walks past that is <em>already</em> a criminal has its
 * {@code lastSeenDay} stamped, which is how a record for a villager who no longer exists can later be
 * told apart from one whose chunk is simply asleep.
 *
 * <p>The decision itself is not here — {@link CriminalJobAssigner} makes it from plain values. This
 * class only gathers those values and writes the result, so that the balance rules stay testable and
 * the entity handling stays in one place.
 */
@Mod.EventBusSubscriber(modid = McaCrime.MOD_ID)
public final class CriminalJobAssignmentSweep {

    /**
     * How far from a player the sweep looks. Deliberately not a config key: it is not a balance dial
     * but the radius within which "loaded" and "observable" mean the same thing, and a larger one
     * would only assign jobs to villagers nobody is near enough to meet.
     */
    private static final double SCAN_RADIUS = 96.0D;

    /** The village-cooldown counter key. One per community, held in the world data's counter map. */
    private static final String COOLDOWN_KEY = "criminalJobs.lastAssignDay.";

    private static int counter;

    private CriminalJobAssignmentSweep() {
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) {
            return;
        }
        int interval = Math.max(1, McaCrimeConfig.COMMON.assignmentScanIntervalTicks.get());
        if (++counter < interval) {
            return;
        }
        counter = 0;
        AssignmentPolicy policy = AssignmentPolicy.fromConfig();
        if (!policy.enableThieves() && !policy.enableFences()) {
            return;
        }
        try {
            sweep(server, policy);
        } catch (Throwable t) {
            McaCrime.LOGGER.debug("MCA: Crime criminal-job sweep failed; continuing", t);
        }
    }

    /** Called by {@code /crime job} debugging and by the tick; separate so a command can force a pass. */
    public static void sweep(MinecraftServer server, AssignmentPolicy policy) {
        WorldCriminalJobService jobs = WorldCriminalJobService.of(server);
        CrimeWorldData world = CrimeWorldData.get(server);
        long today = server.overworld().getGameTime() / 24000L;
        int maxThieves = McaCrimeConfig.COMMON.maxActiveThievesPerJurisdiction.get();
        Map<String, Integer> liveThieves = thievesByCommunity(server, jobs);
        for (ServerLevel level : server.getAllLevels()) {
            if (level.players().isEmpty()) {
                continue;
            }
            Map<Integer, Integer> populations = populations(level);
            for (Entity entity : nearbyVillagers(level)) {
                UUID id = entity.getUUID();
                NpcMuggerEligibility.Facts facts =
                        WorldCriminalJobService.facts(entity, jobs.get(id));
                if (jobs.isCriminal(id)) {
                    if (NpcMuggerEligibility.contradictory(facts)) {
                        // The stale-role repair, and the reason this check sits before the skip: a
                        // villager promoted to guard after being recorded as a criminal was otherwise
                        // walked past on every pass for the rest of the world's life, record intact.
                        jobs.assign(id, CriminalJob.NONE, false);
                        McaCrime.LOGGER.debug(
                                "MCA: Crime cleared a criminal record from law responder {}", id);
                        continue;
                    }
                    jobs.touchSeen(id, today);
                    continue;
                }
                OptionalInt village = McaCompat.getHomeVillageId(entity);
                Candidate candidate = new Candidate(id,
                        McaCompat.isAdultVillager(entity),
                        NpcMuggerEligibility.evaluate(facts, NpcMuggerEligibility.Context.ASSIGNMENT),
                        false,
                        CustodyRegistry.isCaptive(server, id),
                        // MCA's own "do not overwrite this profession" flag is the only quest-critical
                        // signal available; it reads as protected when unbound, which fails safe to
                        // "assign nobody" rather than to "recruit the village's only cleric".
                        McaHandles.available() && McaHandles.isProfessionImportant(entity),
                        village.isPresent(),
                        village.isPresent() ? populations.getOrDefault(village.getAsInt(), 0) : 0,
                        village.isPresent() ? lastAssignDay(world, level, village.getAsInt()) : Long.MIN_VALUE);
                Optional<CriminalJob> rolled =
                        CriminalJobAssigner.roll(candidate, policy, today, level.random::nextDouble);
                if (rolled.isEmpty()) {
                    continue;
                }
                // The cap is counted, not merely rate-limited: the assignment cooldown says how often a
                // village may produce a thief, and a village played in for a month accumulates them one
                // at a time under a cooldown that is satisfied every time (0.7.0).
                String community = rolled.get() == CriminalJob.THIEF && candidate.hasVillage()
                        ? cooldownKey(level, village.getAsInt()) : null;
                if (community != null && !CriminalJobAssigner.jurisdictionAllows(
                        liveThieves.getOrDefault(community, 0), maxThieves)) {
                    continue;
                }
                if (!begin(level, entity, jobs, rolled.get(), candidate.hasVillage())) {
                    // Spec §10.1: a failed reservation or transition must not consume the village's
                    // assignment cooldown as though it had succeeded, so nothing is stamped here and
                    // the cap is not charged for a recruitment that has not committed.
                    continue;
                }
                if (community != null) {
                    liveThieves.merge(community, 1, Integer::sum);
                }
                if (candidate.hasVillage() && rolled.get() != CriminalJob.THIEF) {
                    markAssigned(world, level, village.getAsInt(), today);
                }
            }
        }
    }

    /**
     * Starts one assignment through the route that job actually uses (0.7.2 §10.1).
     *
     * <p>Three routes, one transition service behind all of them. A settlement thief walks to a Mask
     * Station and becomes one only on arrival, so this returns "started" rather than "assigned" and
     * the village cooldown is stamped by the transaction when it commits. A wild thief is the
     * documented stationless exception and commits immediately. A fence is unchanged: occupational
     * exclusivity is specifically a Thief rule (§9.5).
     */
    private static boolean begin(ServerLevel level, Entity entity, WorldCriminalJobService jobs,
                                 CriminalJob job, boolean hasVillage) {
        if (job != CriminalJob.THIEF) {
            jobs.assign(entity.getUUID(), job, !hasVillage);
            return jobs.isCriminal(entity.getUUID());
        }
        if (hasVillage) {
            return ThiefWorksiteService.beginRecruitment(level, entity, OccupationSource.SETTLEMENT_SWEEP);
        }
        return jobs.requestThiefOccupation(
                OccupationRequest.unbound(entity.getUUID(), OccupationSource.WILD, true)).committed();
    }

    /**
     * How many thieves each jurisdiction already has, counted from the persisted records (0.7.0).
     *
     * <p>A record holds no position, so the community is resolved from the villager itself where it is
     * loaded. A thief in an unloaded chunk therefore does not count towards its village's cap: that is
     * the honest answer rather than a guessed one, and the sweep only ever looks at villages a player is
     * standing in anyway, which are precisely the loaded ones.
     */
    private static Map<String, Integer> thievesByCommunity(MinecraftServer server,
                                                           WorldCriminalJobService jobs) {
        Map<String, Integer> counts = new HashMap<>();
        for (var record : jobs.all()) {
            if (record.job() != CriminalJob.THIEF || !record.status().employed()) {
                // A pending recruitment walking to a station is not yet a thief and must not occupy a
                // slot in the jurisdiction cap it has not earned.
                continue;
            }
            for (ServerLevel level : server.getAllLevels()) {
                Entity entity = level.getEntity(record.villager());
                if (entity == null) {
                    continue;
                }
                CrimeCommunityResolver.resolve(entity, level)
                        .ifPresent(key -> counts.merge(COOLDOWN_KEY + key.asString(), 1, Integer::sum));
                break;
            }
        }
        return counts;
    }

    /**
     * Loaded MCA villagers near a player, de-duplicated.
     *
     * <p>Two players standing together would otherwise roll the same villager twice in one pass,
     * which is a doubled chance nobody configured.
     */
    private static List<Entity> nearbyVillagers(ServerLevel level) {
        List<Entity> out = new ArrayList<>();
        Set<UUID> seen = new HashSet<>();
        for (ServerPlayer player : level.players()) {
            AABB box = player.getBoundingBox().inflate(SCAN_RADIUS);
            for (Entity entity : level.getEntities(player, box, McaCompat::isMcaVillager)) {
                if (entity.isAlive() && seen.add(entity.getUUID())) {
                    out.add(entity);
                }
            }
        }
        return out;
    }

    /**
     * MCA's own population figure per village id in this level, or an empty map when MCA cannot be
     * asked. An unreadable population is zero, which keeps fences out of it — a settlement whose size
     * cannot be established has not met the minimum.
     */
    private static Map<Integer, Integer> populations(ServerLevel level) {
        if (!McaHandles.populationAvailable()) {
            return Map.of();
        }
        Map<Integer, Integer> out = new HashMap<>();
        for (Object village : McaHandles.villagesIn(level)) {
            int id = McaHandles.villageIdOf(village);
            if (id >= 0) {
                out.put(id, McaHandles.villagePopulation(village));
            }
        }
        return out;
    }

    /**
     * The day this village last produced a criminal.
     *
     * <p>Stored as the day plus one, because the counter map answers "never recorded" with zero and
     * day zero is a real day. {@link Long#MIN_VALUE} is what the assigner reads as never.
     */
    private static long lastAssignDay(CrimeWorldData world, ServerLevel level, int villageId) {
        String key = cooldownKey(level, villageId);
        if (key == null) {
            return Long.MIN_VALUE;
        }
        long stored = world.actionCounter(key);
        return stored <= 0L ? Long.MIN_VALUE : stored - 1L;
    }

    private static void markAssigned(CrimeWorldData world, ServerLevel level, int villageId, long today) {
        String key = cooldownKey(level, villageId);
        if (key != null) {
            world.setActionCounter(key, today + 1L);
        }
    }

    private static String cooldownKey(ServerLevel level, int villageId) {
        return CrimeCommunityKey.of(level.dimension(), villageId)
                .map(community -> COOLDOWN_KEY + community.asString())
                .orElse(null);
    }
}
