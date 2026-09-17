package dev.otectus.mcacrime.economy;

import dev.otectus.mcacrime.McaCrimeConfig;
import dev.otectus.mcacrime.api.model.CrimeCommunityKey;
import dev.otectus.mcacrime.compat.TownsteadBridge;
import dev.otectus.mcacrime.compat.TownsteadSpiritView;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

import org.jetbrains.annotations.Nullable;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Which {@link EconomyProfile} a settlement is on right now.
 *
 * <h2>Derived, never stored</h2>
 *
 * <p>A profile is a reading of the settlement mod, so it is re-read rather than persisted: a village
 * that grows is a village whose profile changes, and a stored one would be a second copy drifting away
 * from the buildings that produced it. What is stored is a short-lived cache — the same
 * {@code townstead.snapshotCacheTicks} bound every other Townstead reading uses — because the fine,
 * bounty and fence paths each ask on a player interaction and a reflective village lookup per click is
 * more than the answer is worth.
 *
 * <h2>TOWN is the answer to every question it cannot answer</h2>
 *
 * <p>No settlement mod, no spirit capability, no community, an exception: all of them produce
 * {@link EconomyProfile#TOWN}, which is the neutral profile and changes nothing at all. That is what
 * makes {@code townstead.economyProfiles} report as <em>degraded</em> rather than off when the
 * capability is missing — the feature is on, every village is being priced, and every one of them is
 * being priced as an ordinary town.
 */
public final class EconomyProfileResolver {

    /** Buildings at or above which a settlement is prosperous. */
    public static final int PROSPEROUS_BUILDINGS = 12;

    /** Spirit points at or above which a settlement is prosperous, whatever its building count. */
    public static final int PROSPEROUS_SPIRIT = 24;

    /** Buildings below which a settlement is a frontier hamlet. */
    public static final int FRONTIER_BUILDINGS = 4;

    /** How many communities the cache remembers. A ceiling on memory, not on villages. */
    private static final int MAX_CACHED = 256;

    private record Reading(EconomyProfile profile, long readAt) {
    }

    private static final Map<String, Reading> CACHE = new LinkedHashMap<>();

    private EconomyProfileResolver() {
    }

    /** Whether village character is allowed to shape prices at all. Any throw reads as off. */
    public static boolean enabled() {
        try {
            return McaCrimeConfig.COMMON.townsteadEnabled.get()
                    && McaCrimeConfig.COMMON.townsteadEconomyProfiles.get();
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * The profile for one community, using whichever server is running.
     *
     * <p>The no-server overload exists because two of the three plug points — the fine quote and the
     * bounty price — are reached from pure-ish code that was never given one, and threading a
     * {@code MinecraftServer} through them to read an optional multiplier would be a worse trade than
     * asking the lifecycle hook.
     */
    public static EconomyProfile of(@Nullable CrimeCommunityKey community) {
        // The switch is read before the lifecycle hook, not after. With the feature off -- which is
        // every server that has not asked for it, and every unit test -- this must not touch a NeoForge
        // class at all, and the class it would touch is one no test has bootstrapped.
        return !enabled() || community == null
                ? EconomyProfile.TOWN
                : of(ServerLifecycleHooks.getCurrentServer(), community);
    }

    /** The profile for one community. {@link EconomyProfile#TOWN} whenever it cannot be read. */
    public static EconomyProfile of(@Nullable MinecraftServer server,
                                    @Nullable CrimeCommunityKey community) {
        if (!enabled() || server == null || community == null) {
            return EconomyProfile.TOWN;
        }
        try {
            long now = server.overworld().getGameTime();
            String key = community.asString();
            EconomyProfile cached = cached(key, now);
            if (cached != null) {
                return cached;
            }
            EconomyProfile profile = read(server, community);
            remember(key, profile, now);
            return profile;
        } catch (Throwable ignored) {
            // The settlement mod is third-party code reached reflectively. A price is never worth an
            // exception thrown out of a trade screen.
            return EconomyProfile.TOWN;
        }
    }

    /**
     * The rule itself: buildings and spirit in, profile out.
     *
     * <p>Pure, and the two inputs are the ones the bridge can actually supply. Population is not among
     * them: Townstead's facade exposes no village headcount, and inferring one from loaded entities
     * would make a village poorer at night.
     *
     * @param contributingBuildings how many completed buildings the settlement's spirit is derived from
     * @param spiritTotal           the settlement's total spirit points
     */
    public static EconomyProfile select(int contributingBuildings, int spiritTotal) {
        int buildings = Math.max(0, contributingBuildings);
        int spirit = Math.max(0, spiritTotal);
        if (buildings >= PROSPEROUS_BUILDINGS || spirit >= PROSPEROUS_SPIRIT) {
            return EconomyProfile.PROSPEROUS;
        }
        if (buildings > 0 && buildings < FRONTIER_BUILDINGS) {
            return EconomyProfile.FRONTIER;
        }
        // Zero buildings is "nothing was read", not "an empty village": a settlement with no reading
        // must price exactly as it did before this feature existed.
        return EconomyProfile.TOWN;
    }

    /** Drops every cached reading. Server stop, a config reload, and tests. */
    public static synchronized void clearAll() {
        CACHE.clear();
    }

    /** How many communities have a cached profile. Operator diagnostics and tests. */
    public static synchronized int cachedCount() {
        return CACHE.size();
    }

    private static EconomyProfile read(MinecraftServer server, CrimeCommunityKey community) {
        ServerLevel level = null;
        for (ServerLevel candidate : server.getAllLevels()) {
            if (candidate.dimension().location().equals(community.dimension())) {
                level = candidate;
                break;
            }
        }
        if (level == null) {
            return EconomyProfile.TOWN;
        }
        TownsteadSpiritView spirit = TownsteadBridge.spirit(level, community.villageId()).orElse(null);
        return spirit == null
                ? EconomyProfile.TOWN
                : select(spirit.contributingBuildings(), spirit.total());
    }

    private static synchronized EconomyProfile cached(String key, long now) {
        Reading reading = CACHE.get(key);
        if (reading == null) {
            return null;
        }
        long window = Math.max(1, cacheTicks());
        if (now < reading.readAt() || now - reading.readAt() >= window) {
            CACHE.remove(key);
            return null;
        }
        return reading.profile();
    }

    private static synchronized void remember(String key, EconomyProfile profile, long now) {
        if (CACHE.size() >= MAX_CACHED) {
            CACHE.remove(CACHE.keySet().iterator().next());
        }
        CACHE.put(key, new Reading(profile, now));
    }

    private static int cacheTicks() {
        try {
            return McaCrimeConfig.COMMON.townsteadSnapshotCacheTicks.get();
        } catch (Throwable ignored) {
            return 20;
        }
    }
}
