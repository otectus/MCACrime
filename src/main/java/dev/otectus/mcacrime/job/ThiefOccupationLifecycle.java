package dev.otectus.mcacrime.job;

import dev.otectus.mcacrime.McaCrime;
import dev.otectus.mcacrime.McaCrimeConfig;
import dev.otectus.mcacrime.captivity.CustodyRegistry;
import dev.otectus.mcacrime.compat.McaCompat;
import dev.otectus.mcacrime.compat.OccupationCompat;
import dev.otectus.mcacrime.detect.EntitySelectors;
import dev.otectus.mcacrime.state.world.CriminalVillagerRecord;
import dev.otectus.mcacrime.state.world.CrimeWorldData;
import dev.otectus.mcacrime.state.world.ServerMutationGate;
import dev.otectus.mcacrime.state.world.WorksiteRef;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;

import org.jetbrains.annotations.Nullable;

/**
 * The Thief occupation state machine, reconciled against the world (0.7.2 §10.3–10.5).
 *
 * <h2>What this is for</h2>
 *
 * <p>A profession, a POI ticket and a Crime record live in three different files with three different
 * save schedules, and spec §"Crash recovery" is explicit that keeping them in step is reconciliation
 * rather than a transaction. This runs on a slow cadence over records whose villager is actually
 * loaded, and repairs the disagreements that are legitimately repairable: a station that was broken, a
 * villager another mod promoted to guard, a novice whose claim went away, a capability that came back.
 *
 * <h2>What it refuses to do</h2>
 *
 * <p>It never loads a chunk to find out. An unloaded station is unresolved, not destroyed (§10.4), and
 * an unloaded villager is skipped entirely — including for the grace clock, which is measured in
 * <em>loaded</em> ticks precisely so a thief cannot be retired while nobody is there to see it.
 */
public final class ThiefOccupationLifecycle {

    /** How often the reconciliation runs. Everything below is measured in these ticks. */
    public static final int INTERVAL_TICKS = 20;
    /** Staggered replacement-station searches, so a village of thieves never all search at once. */
    public static final int RETRY_MIN_TICKS = 200;
    public static final int RETRY_MAX_TICKS = 400;
    /** Loaded ticks a novice may be without its claim before the occupation is given up (§10.4). */
    public static final int NOVICE_GRACE_TICKS = 1200;
    /** One Minecraft day of eligible employment after a successful visit earns establishment (§10.4). */
    public static final int ESTABLISHMENT_TICKS = 24000;

    private static int counter;

    private ThiefOccupationLifecycle() {
    }

    // --- pure decisions --------------------------------------------------------------------------

    /** A per-villager retry delay in [{@value #RETRY_MIN_TICKS}, {@value #RETRY_MAX_TICKS}]. */
    public static int retryDelay(long personalitySeed) {
        int span = RETRY_MAX_TICKS - RETRY_MIN_TICKS + 1;
        return RETRY_MIN_TICKS + (int) Math.floorMod(personalitySeed, span);
    }

    /**
     * Whether this reconciliation pass crossed the villager's retry boundary.
     *
     * <p>Derived from the clock and the seed rather than from a stored "last attempt" stamp: the
     * stamp would have to be persisted and saved, and this answers the same question for free and
     * survives a restart without pretending the thief has been searching the whole time.
     */
    public static boolean retryDue(long now, int interval, int delay, int stagger) {
        if (delay <= 0 || interval <= 0) {
            return false;
        }
        return Math.floorDiv(now + stagger, delay) != Math.floorDiv(now - interval + stagger, delay);
    }

    /** Novice grace is measured in loaded ticks, and a clock that went backwards has not elapsed. */
    public static boolean noviceGraceExpired(long unboundSince, long now) {
        return unboundSince > 0L && now >= unboundSince && now - unboundSince >= NOVICE_GRACE_TICKS;
    }

    /** Establishment needs a successful station visit <em>and</em> a day of eligible employment. */
    public static boolean establishmentDue(long employedTicks, long lastVisitAt) {
        return lastVisitAt > 0L && employedTicks >= ESTABLISHMENT_TICKS;
    }

    /** The state a committed occupation should be in, given its claim and its milestone. */
    public static OccupationStatus resolve(boolean bound, boolean established) {
        if (bound) {
            return established ? OccupationStatus.ACTIVE_BOUND_ESTABLISHED : OccupationStatus.ACTIVE_BOUND_NOVICE;
        }
        return established ? OccupationStatus.ESTABLISHED_UNBOUND : OccupationStatus.PENDING;
    }

    // --- live ------------------------------------------------------------------------------------

    /** Called every server tick; does real work once every {@value #INTERVAL_TICKS}. */
    public static void tick(MinecraftServer server) {
        if (server == null || !ServerMutationGate.allows(server)) {
            return;
        }
        if (++counter < INTERVAL_TICKS) {
            return;
        }
        counter = 0;
        try {
            reconcile(server);
        } catch (Throwable t) {
            McaCrime.LOGGER.debug("MCA: Crime thief occupation reconciliation failed; continuing", t);
        }
    }

    private static void reconcile(MinecraftServer server) {
        CrimeWorldData world = CrimeWorldData.get(server);
        WorldCriminalJobService jobs = WorldCriminalJobService.of(server);
        boolean enabled = McaCrimeConfig.COMMON.enableThieves.get();
        for (CriminalVillagerRecord record : world.criminalVillagers()) {
            if (record.job() != CriminalJob.THIEF && record.status() != OccupationStatus.PENDING) {
                continue;
            }
            Entity entity = findLoaded(server, record.villager());
            if (entity == null || !(entity.level() instanceof ServerLevel level)) {
                // Unloaded is unresolved: no grace accrues, no claim is released, nothing is retired.
                ThiefWorkRegistry.clearEmployed(record.villager());
                continue;
            }
            if (record.status() == OccupationStatus.PENDING && record.job() == CriminalJob.NONE) {
                ThiefWorksiteService.tickPending(level, entity, record);
                continue;
            }
            reconcileEmployed(server, level, entity, record, world, jobs, enabled);
        }
    }

    private static void reconcileEmployed(MinecraftServer server, ServerLevel level, Entity entity,
                                          CriminalVillagerRecord record, CrimeWorldData world,
                                          WorldCriminalJobService jobs, boolean enabled) {
        // A law responder wins over a historical Thief overlay, always and immediately (spec §11.6).
        if (EntitySelectors.isResponder(entity)) {
            ThiefWorkRegistry.clearEmployed(record.villager());
            jobs.retireOccupation(record.villager(), OccupationSource.MIGRATION);
            return;
        }
        // An explicit external profession change is respected, not fought (spec §"Other mods").
        if (!CriminalProfessions.THIEF_ID.equals(McaCompat.getProfessionId(entity).orElse(null))) {
            if (McaCompat.getProfessionId(entity).isPresent()) {
                ThiefWorkRegistry.clearEmployed(record.villager());
                dev.otectus.mcacrime.ai.thief.ThiefTicker.stop(record.villager());
                jobs.retireOccupation(record.villager(), OccupationSource.MIGRATION);
            }
            return;
        }
        if (!OccupationCompat.missingCapability().isEmpty()) {
            ThiefWorkRegistry.clearEmployed(record.villager());
            if (record.status() != OccupationStatus.SUSPENDED) {
                world.putCriminalVillager(record.withStatus(OccupationStatus.SUSPENDED));
            }
            return;
        }

        long now = level.getGameTime();
        CriminalVillagerRecord updated = record;
        if (record.status() == OccupationStatus.SUSPENDED) {
            // The capability came back: return to the state the claim and the milestone describe.
            updated = updated.withStatus(resolve(record.worksite() != null, record.establishedAt() > 0L));
        }

        boolean captive = CustodyRegistry.isCaptive(server, record.villager());
        if (enabled && !captive) {
            updated = updated.withEmployedTicks(updated.employedTicks() + INTERVAL_TICKS);
        }

        WorksiteRef site = updated.worksite();
        if (site != null) {
            boolean inspectable = site.matches(level) && level.isLoaded(site.pos());
            if (inspectable && !OccupationCompat.poiExists(level, site.pos(), CrimePoiTypes.MASK_STATION_KEY)) {
                updated = loseStation(level, updated, now);
            } else if (inspectable && !captive
                    && ThiefWorksiteService.arrived(site.pos(), entity.getX(), entity.getY(), entity.getZ(),
                            ThiefWorksiteService.ARRIVAL_DISTANCE)) {
                updated = updated.withVisit(now);
            }
        }

        if (updated.status() == OccupationStatus.ACTIVE_BOUND_NOVICE
                && establishmentDue(updated.employedTicks(), updated.lastVisitAt())) {
            updated = updated.withEstablishedAt(now).withStatus(OccupationStatus.ACTIVE_BOUND_ESTABLISHED);
        }

        if (updated.status() == OccupationStatus.PENDING && updated.job() == CriminalJob.THIEF) {
            // A novice past its grace period: give the occupation up through the one transition.
            if (noviceGraceExpired(updated.unboundSince(), now)) {
                world.putCriminalVillager(updated);
                ThiefWorkRegistry.clearEmployed(record.villager());
                jobs.retireOccupation(record.villager(), OccupationSource.STATION_RECRUITMENT);
                return;
            }
        } else if (updated.status() == OccupationStatus.ESTABLISHED_UNBOUND && enabled
                && updated.reservation() == null
                && retryDue(now, INTERVAL_TICKS, retryDelay(updated.personalitySeed()),
                        (int) Math.floorMod(updated.personalitySeed(), 200L))) {
            WorksiteRef replacement = ThiefWorksiteService.reserveNearest(level, entity).orElse(null);
            if (replacement != null) {
                updated = updated.withReservation(replacement, now);
            }
        }

        if (updated.reservation() != null && updated.job() == CriminalJob.THIEF) {
            updated = claimReplacement(level, entity, updated, jobs, now);
        }

        if (!updated.equals(record)) {
            world.putCriminalVillager(updated);
        }
        if (updated.status().mayMug()) {
            ThiefWorkRegistry.markEmployed(record.villager());
        } else {
            ThiefWorkRegistry.clearEmployed(record.villager());
        }
    }

    /**
     * The station is gone. A novice starts its grace clock; an established Thief simply becomes
     * unbound and keeps looking (spec §10.4's two distinct rows).
     */
    private static CriminalVillagerRecord loseStation(ServerLevel level, CriminalVillagerRecord record,
                                                      long now) {
        CriminalVillagerRecord out = record.withWorksite(null);
        if (record.status().established()) {
            return out.withStatus(OccupationStatus.ESTABLISHED_UNBOUND);
        }
        return out.withStatus(OccupationStatus.PENDING)
                .withUnboundSince(record.unboundSince() > 0L ? record.unboundSince() : now);
    }

    /** Walks an unbound Thief to its replacement reservation and commits on arrival. */
    private static CriminalVillagerRecord claimReplacement(ServerLevel level, Entity entity,
                                                           CriminalVillagerRecord record,
                                                           WorldCriminalJobService jobs, long now) {
        WorksiteRef site = record.reservation();
        if (site == null) {
            return record;
        }
        if (!site.matches(level)
                || !OccupationCompat.poiExists(level, site.pos(), CrimePoiTypes.MASK_STATION_KEY)
                || ThiefWorksiteService.reservationExpired(record.reservationAt(), now,
                        ThiefWorksiteService.RESERVATION_TIMEOUT_TICKS)) {
            OccupationCompat.releaseTicket(level, site.pos());
            return record.withReservation(null, 0L);
        }
        if (!ThiefWorksiteService.arrived(site.pos(), entity.getX(), entity.getY(), entity.getZ(),
                ThiefWorksiteService.ARRIVAL_DISTANCE)) {
            return record;
        }
        OccupationTransitionResult result = jobs.requestThiefOccupation(OccupationRequest.station(
                record.villager(), OccupationSource.STATION_RECRUITMENT, site, true,
                record.status().established()));
        if (!result.committed()) {
            OccupationCompat.releaseTicket(level, site.pos());
            return record.withReservation(null, 0L);
        }
        // requestThiefOccupation has already written the committed record; re-read is the caller's job.
        return record.withReservation(null, 0L).withWorksite(site).withStatus(result.status());
    }

    /** Death: release the claim and retire, after the existing death/loot handling has run. */
    public static void onDeath(MinecraftServer server, java.util.UUID villager) {
        if (server == null || villager == null) {
            return;
        }
        ThiefWorkRegistry.clearEmployed(villager);
        WorldCriminalJobService.of(server).retireOccupation(villager, OccupationSource.MIGRATION);
    }

    /** Server stop: drop the transient membership. Nothing persisted changes. */
    public static void clearAll() {
        counter = 0;
        ThiefWorkRegistry.clearAll();
    }

    @Nullable
    private static Entity findLoaded(MinecraftServer server, java.util.UUID villager) {
        for (ServerLevel level : server.getAllLevels()) {
            Entity entity = level.getEntity(villager);
            if (entity != null && entity.isAlive()) {
                return entity;
            }
        }
        return null;
    }
}
