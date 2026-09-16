package dev.otectus.mcacrime.job;

import dev.otectus.mcacrime.McaCrime;
import dev.otectus.mcacrime.compat.OccupationCompat;
import dev.otectus.mcacrime.state.world.CriminalVillagerRecord;
import dev.otectus.mcacrime.state.world.CrimeWorldData;
import net.minecraft.core.BlockPos;
import dev.otectus.mcacrime.state.world.WorksiteRef;
import net.minecraft.core.GlobalPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.WalkTarget;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.level.pathfinder.Path;

import javax.annotation.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Finds a Mask Station for a candidate, reserves its native ticket, walks them to it, and hands the
 * result to the one occupation transaction (0.7.2 §10.1–10.2).
 *
 * <h2>Why Crime does the walking</h2>
 *
 * <p>The acquisition boundary deliberately hides Mask Stations from every non-Thief acquirable
 * predicate, which is what stops an unemployed villager becoming a Thief by accident — and that same
 * filter means vanilla's own approach machinery cannot be used to bring a candidate to one, because
 * its potential-site validator would reject the memory and release the ticket mid-walk. So the
 * reservation is held in the Crime record instead, and the approach is a walk target rather than a
 * second movement controller.
 *
 * <h2>Bounds</h2>
 *
 * <p>Radius 48, at most five path candidates, and a 400-tick reservation timeout. These are the
 * numbers spec §10.2 asks for ("bound search radius and path attempts, release failed reservations
 * after a short tested acquisition timeout") and they are constants rather than config keys because
 * they describe the cost of the search, not the balance of the feature.
 */
public final class ThiefWorksiteService {

    /** Matches MCA's own job search radius, so a station it would find is a station this finds. */
    public static final int SEARCH_RADIUS = 48;
    /** At most five stations are path-tested per attempt, nearest first. */
    public static final int MAX_PATH_CANDIDATES = 5;
    /** A reservation that has not been reached in twenty seconds is given back. */
    public static final int RESERVATION_TIMEOUT_TICKS = 400;
    /** Vanilla's own arrival distance for a job site. */
    public static final double ARRIVAL_DISTANCE = 2.0D;

    private ThiefWorksiteService() {
    }

    // --- pure decisions --------------------------------------------------------------------------

    /** The nearest {@code max} candidates, closest first. A bounded list is the whole point. */
    public static List<BlockPos> chooseCandidates(List<BlockPos> found, BlockPos origin, int max) {
        if (found == null || found.isEmpty() || origin == null || max <= 0) {
            return List.of();
        }
        List<BlockPos> sorted = new ArrayList<>(found);
        sorted.sort(Comparator.comparingDouble(pos -> pos.distSqr(origin)));
        return List.copyOf(sorted.subList(0, Math.min(max, sorted.size())));
    }

    /**
     * Whether a reservation has been held too long without arriving.
     *
     * <p>A clock that went backwards — a restored backup, a {@code /time set} — reads as "not expired"
     * rather than as a reservation that expires again every tick until the old time comes round.
     */
    public static boolean reservationExpired(long reservationAt, long now, int timeoutTicks) {
        return reservationAt > 0L && now >= reservationAt && now - reservationAt >= timeoutTicks;
    }

    public static boolean arrived(BlockPos site, double x, double y, double z, double distance) {
        return site != null && site.getCenter().distanceToSqr(x, y, z) <= distance * distance;
    }

    // --- live ------------------------------------------------------------------------------------

    /**
     * Reserves the nearest reachable station for this villager, or nothing.
     *
     * <p>The ticket is taken with an exact-position {@code take}, so the station reserved is the
     * station that was path-tested and never a different one a wider search happened to reach first.
     */
    public static Optional<WorksiteRef> reserveNearest(ServerLevel level, Entity entity) {
        if (!(entity instanceof PathfinderMob mob) || level == null) {
            return Optional.empty();
        }
        for (BlockPos candidate : chooseCandidates(available(level, entity.blockPosition()),
                entity.blockPosition(), MAX_PATH_CANDIDATES)) {
            Path path = mob.getNavigation().createPath(candidate, 1);
            if (path == null || !path.canReach()) {
                continue;
            }
            if (OccupationCompat.takeExact(level, candidate, CrimePoiTypes.MASK_STATION_KEY).isPresent()) {
                return Optional.of(WorksiteRef.of(level, candidate));
            }
        }
        return Optional.empty();
    }

    /**
     * Mask Stations with a free ticket within the search radius, in loaded chunks only.
     *
     * <p>The loaded filter is not an optimisation: spec §10.2 forbids forcing a destination chunk, and
     * a candidate whose chunk is asleep cannot be path-tested honestly anyway.
     */
    private static List<BlockPos> available(ServerLevel level, BlockPos origin) {
        try {
            return level.getPoiManager()
                    .findAll(holder -> holder.is(CrimePoiTypes.MASK_STATION_KEY),
                            pos -> level.isLoaded(pos), origin, SEARCH_RADIUS,
                            PoiManager.Occupancy.HAS_SPACE)
                    .limit(64L)
                    .map(BlockPos::immutable)
                    .toList();
        } catch (Throwable t) {
            McaCrime.LOGGER.debug("MCA: Crime could not search for Mask Stations near {}", origin, t);
            return List.of();
        }
    }

    /**
     * Starts a recruitment: reserve a station and persist the intent as a pending record.
     *
     * <p>The record is written <em>before</em> the villager walks anywhere and carries
     * {@link CriminalJob#NONE} until the transaction commits, so a candidate in transit is never
     * counted as a thief, never mugs anybody, and never consumes the village's assignment cooldown —
     * and a server that stops mid-walk still knows which ticket to give back.
     */
    public static boolean beginRecruitment(ServerLevel level, Entity entity, OccupationSource source) {
        CrimeWorldData world = CrimeWorldData.get(level.getServer());
        UUID villager = entity.getUUID();
        if (!world.hasCriminalCapacityFor(villager)) {
            return false;
        }
        CriminalVillagerRecord existing = world.criminalVillager(villager);
        if (existing != null && (existing.job() != CriminalJob.NONE || existing.reservation() != null)) {
            return false;
        }
        WorksiteRef site = reserveNearest(level, entity).orElse(null);
        if (site == null) {
            return false;
        }
        long now = level.getGameTime();
        CriminalVillagerRecord record = (existing == null
                ? CriminalVillagerRecord.fresh(villager, CriminalJob.NONE, now / 24000L, false,
                        villager.getMostSignificantBits() ^ villager.getLeastSignificantBits(), source)
                : existing.withSource(source))
                .withStatus(OccupationStatus.PENDING)
                .withReservation(site, now);
        if (!world.putCriminalVillager(record).stored()) {
            OccupationCompat.releaseTicket(level, site.pos());
            return false;
        }
        approach(entity, site);
        return true;
    }

    /**
     * Moves a pending recruitment one step: arrive and commit, or time out and give the ticket back.
     *
     * @return true when the record still has work in progress
     */
    public static boolean tickPending(ServerLevel level, Entity entity, CriminalVillagerRecord record) {
        WorksiteRef site = record.reservation();
        if (site == null) {
            return false;
        }
        CrimeWorldData world = CrimeWorldData.get(level.getServer());
        long now = level.getGameTime();
        if (!site.matches(level)
                || !OccupationCompat.poiExists(level, site.pos(), CrimePoiTypes.MASK_STATION_KEY)
                || reservationExpired(record.reservationAt(), now, RESERVATION_TIMEOUT_TICKS)) {
            abandon(level, record, world);
            return false;
        }
        if (!arrived(site.pos(), entity.getX(), entity.getY(), entity.getZ(), ARRIVAL_DISTANCE)) {
            approach(entity, site);
            return true;
        }
        OccupationTransitionResult result = WorldCriminalJobService.of(level.getServer())
                .requestThiefOccupation(OccupationRequest.station(record.villager(),
                        record.source() == OccupationSource.UNKNOWN
                                ? OccupationSource.STATION_RECRUITMENT : record.source(),
                        site, true, false));
        if (!result.committed()) {
            abandon(level, record, world);
        }
        return false;
    }

    /**
     * Gives a failed reservation back and forgets it.
     *
     * <p>Spec §10.1: "a failed reservation or transition does not consume the village's assignment
     * cooldown as though it succeeded" — which is why the cooldown is stamped by the sweep only after
     * a committed transition, and why this leaves a candidate with no record rather than a retired one.
     */
    public static void abandon(ServerLevel level, CriminalVillagerRecord record, CrimeWorldData world) {
        WorksiteRef site = record.reservation();
        if (site != null && site.matches(level)) {
            OccupationCompat.releaseTicket(level, site.pos());
        }
        if (record.job() == CriminalJob.NONE) {
            world.removeCriminalVillager(record.villager());
        } else {
            world.putCriminalVillager(record.withReservation(null, 0L));
        }
    }

    /**
     * Points the villager at its reservation using the brain's own walk target.
     *
     * <p>A walk target rather than a navigation call, because spec §10.3 warns against "two
     * simultaneous movement owners": {@code MoveToTargetSink} is already the villager's mover, and
     * handing it a destination is cooperation rather than a fight.
     */
    private static void approach(Entity entity, WorksiteRef site) {
        if (entity instanceof Villager villager) {
            villager.getBrain().setMemory(MemoryModuleType.WALK_TARGET,
                    new WalkTarget(site.pos(), 0.5F, 1));
        }
    }

    /** The reservation a record is holding, for the mutator's ownership check. */
    @Nullable
    public static WorksiteRef heldReservation(@Nullable CriminalVillagerRecord record) {
        return record == null ? null : record.reservation();
    }
}
