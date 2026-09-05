package dev.otectus.mcacrime.job;

import dev.otectus.mcacrime.McaCrime;
import dev.otectus.mcacrime.McaCrimeConfig;
import dev.otectus.mcacrime.captivity.CustodyRegistry;
import dev.otectus.mcacrime.compat.McaCompat;
import dev.otectus.mcacrime.compat.mca.McaHandles;
import dev.otectus.mcacrime.api.model.CrimeCommunityKey;
import dev.otectus.mcacrime.job.CriminalJobAssigner.AssignmentPolicy;
import dev.otectus.mcacrime.job.CriminalJobAssigner.Candidate;
import dev.otectus.mcacrime.state.world.CrimeWorldData;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

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
@EventBusSubscriber(modid = McaCrime.MOD_ID)
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
    public static void onServerTick(ServerTickEvent.Post event) {
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
        for (ServerLevel level : server.getAllLevels()) {
            if (level.players().isEmpty()) {
                continue;
            }
            Map<Integer, Integer> populations = populations(level);
            for (Entity entity : nearbyVillagers(level)) {
                UUID id = entity.getUUID();
                if (jobs.isCriminal(id)) {
                    jobs.touchSeen(id, today);
                    continue;
                }
                OptionalInt village = McaCompat.getHomeVillageId(entity);
                Candidate candidate = new Candidate(id,
                        McaCompat.isAdultVillager(entity),
                        McaCompat.isGuard(entity) || McaCompat.isArcher(entity),
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
                jobs.assign(id, rolled.get(), !candidate.hasVillage());
                if (candidate.hasVillage()) {
                    markAssigned(world, level, village.getAsInt(), today);
                }
            }
        }
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
