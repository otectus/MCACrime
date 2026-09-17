package dev.otectus.mcacrime.civic;

import dev.otectus.mcacrime.McaCrime;
import dev.otectus.mcacrime.McaCrimeConfig;
import dev.otectus.mcacrime.api.model.CrimeCommunityKey;
import dev.otectus.mcacrime.api.model.CrimePublicView;
import dev.otectus.mcacrime.api.model.CrimeRecordView;
import dev.otectus.mcacrime.enforcement.GuardDutyService;
import dev.otectus.mcacrime.facility.CrimeFacilityService;
import dev.otectus.mcacrime.facility.FacilityAssignment;
import dev.otectus.mcacrime.facility.FacilityRole;
import dev.otectus.mcacrime.ledger.CrimeRecord;
import dev.otectus.mcacrime.memory.CrimeReport;
import dev.otectus.mcacrime.state.world.CrimeWorldData;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Assembles {@link VillageSecurityView} from the world, once, for a caller that asked.
 *
 * <h2>Derived, never stored</h2>
 *
 * <p>Nothing here is persisted and nothing is cached across ticks. A security score written to disk
 * would be a second copy of facts that already exist in the ledger and the facility table, and the two
 * would disagree the first time a case was pardoned or a facility removed — with the stale copy being
 * the one a player saw. Recomputing is cheap enough for the two callers that exist (an operator command
 * and a rate-limited request packet) and is the only version that cannot drift.
 *
 * <h2>Cost, and why it is bounded the way it is</h2>
 *
 * <p>The ledger has no index by community, so the incident scan is a walk. It is bounded three ways:
 * the ledger itself is capped, records outside the decay window are skipped without any further work,
 * and the kept list stops at {@link #MAX_INCIDENTS}. The guard scan is {@code GuardDutyService}'s, which
 * is already bounded to loaded residents.
 */
public final class VillageSecurityService {

    /**
     * How many public incidents one computation keeps.
     *
     * <p>A ceiling on the evidence, not on the truth: incident pressure is capped anyway, so the
     * hundredth recent theft in one village cannot change the score, and collecting it would only cost
     * memory to reach the same number.
     */
    public static final int MAX_INCIDENTS = 64;

    private VillageSecurityService() {
    }

    /**
     * The security view for one community.
     *
     * <p>Empty only when there is nothing to compute against — no server, no community. A village with
     * no guards, no facilities and no history is not empty, it is
     * {@link VillageSecurityView.Rating#STEADY} with notes saying what is missing, which is the answer
     * an operator actually needs.
     */
    public static Optional<VillageSecurityView> of(@Nullable ServerLevel level,
                                                   @Nullable CrimeCommunityKey community) {
        MinecraftServer server = level == null ? null : level.getServer();
        if (server == null || community == null) {
            return Optional.empty();
        }
        try {
            long now = server.overworld().getGameTime();
            long window = VillageSecurityView.DEFAULT_WINDOW_TICKS;
            List<CrimePublicView.PublicIncident> incidents = publicIncidents(server, community, now, window);

            GuardDutyService.DutyView duty =
                    GuardDutyService.inspect(level, String.valueOf(community.villageId())).orElse(null);

            return Optional.of(VillageSecurityView.compute(community, now, window, incidents,
                    duty == null ? 0 : duty.guards(),
                    duty == null ? 0 : duty.onDuty(),
                    duty == null ? 0 : duty.shortfall(),
                    facilities(server, community, FacilityRole.JAIL_CELL),
                    facilities(server, community, FacilityRole.CARE_ROOM),
                    facilities(server, community, FacilityRole.GUARD_POST)));
        } catch (Throwable t) {
            McaCrime.LOGGER.debug("MCA: Crime — village security view failed; returning empty", t);
            return Optional.empty();
        }
    }

    /**
     * How far a "which settlement am I standing in?" question looks.
     *
     * <p>Small on purpose. A player standing in a field between two villages is standing in neither,
     * and answering with whichever one happened to be closer would attach a security reading to a place
     * that is not it.
     */
    public static final int LOCAL_RADIUS = 32;

    /**
     * The settlement a position belongs to, if any.
     *
     * <p>Two ways of asking, in order of how much they actually know. Townstead's own building lookup
     * is authoritative about what settlement a block belongs to and is used first when it is bound. The
     * fallback is the nearest resident's home village, which is what MCA: Crime has always used to decide
     * whose business a crime is — it is looser, but it is the same looseness every other community
     * decision in this mod already has, which is better than a second, differently-wrong answer.
     */
    public static Optional<CrimeCommunityKey> communityNear(@Nullable ServerLevel level,
                                                            @Nullable net.minecraft.core.BlockPos pos) {
        if (level == null || pos == null) {
            return Optional.empty();
        }
        try {
            Optional<CrimeCommunityKey> fromBuilding = dev.otectus.mcacrime.compat.TownsteadBridge
                    .buildingAt(level, pos).asOptional()
                    .flatMap(building -> CrimeCommunityKey.of(level.dimension().location(),
                            building.villageId()));
            if (fromBuilding.isPresent()) {
                return fromBuilding;
            }
            net.minecraft.world.phys.AABB box = new net.minecraft.world.phys.AABB(pos).inflate(LOCAL_RADIUS);
            for (net.minecraft.world.entity.LivingEntity resident : level.getEntitiesOfClass(
                    net.minecraft.world.entity.LivingEntity.class, box,
                    entity -> entity.isAlive()
                            && dev.otectus.mcacrime.compat.McaCompat.isMcaVillager(entity))) {
                java.util.OptionalInt village =
                        dev.otectus.mcacrime.compat.McaCompat.getHomeVillageId(resident);
                if (village.isPresent()) {
                    return CrimeCommunityKey.of(level.dimension().location(), village.getAsInt());
                }
            }
            return Optional.empty();
        } catch (Throwable t) {
            McaCrime.LOGGER.debug("MCA: Crime — locating a settlement failed; returning empty", t);
            return Optional.empty();
        }
    }

    /**
     * Every incident this community publicly knows about, from any offender, inside the window.
     *
     * <p>Per community rather than per offender, which is the one way this differs from
     * {@code McaCrimeApi.publicView}: a village's sense of safety is about what has happened here, not
     * about who did it. The knowledge rule is still {@link CrimePublicView#isPublic}'s, applied case by
     * case, so an unwitnessed burglary is as invisible to the score as it is to a reaction.
     *
     * <p>The report index is consulted per offender and memoised across the scan, because
     * {@code reportsAgainst} is keyed by suspect and a village's incidents are spread across a handful
     * of them.
     */
    public static List<CrimePublicView.PublicIncident> publicIncidents(MinecraftServer server,
                                                                       CrimeCommunityKey community,
                                                                       long now, long windowTicks) {
        CrimeWorldData data = CrimeWorldData.get(server);
        boolean observations = observationsEnabled();
        double confidence = confidenceThreshold();

        // suspect -> case ids an accepted report names. Built lazily: most offenders in a long-lived
        // ledger have no reports at all, and a village normally has very few distinct offenders.
        java.util.Map<UUID, Set<UUID>> reportedBySuspect = new java.util.HashMap<>();

        List<CrimePublicView.PublicIncident> incidents = new ArrayList<>();
        for (CrimeRecord record : data.recordsInCommunity(community, MAX_INCIDENTS * 4)) {
            CrimeRecordView view = record.view();
            if (VillageSecurityView.decay(view.committedGameTime(), now, windowTicks) <= 0.0D) {
                continue; // outside the window: it cannot affect the score, so it is not evidence
            }
            Set<UUID> reported = observations
                    ? reportedBySuspect.computeIfAbsent(view.offenderId(),
                            offender -> supportedCaseIds(data, offender, confidence))
                    : Set.of();
            if (!CrimePublicView.isPublic(view, community, observations, reported::contains)) {
                continue;
            }
            incidents.add(new CrimePublicView.PublicIncident(view.id(), view.crimeType(),
                    view.committedGameTime(), view.resolution()));
            if (incidents.size() >= MAX_INCIDENTS) {
                break;
            }
        }
        return incidents;
    }

    private static Set<UUID> supportedCaseIds(CrimeWorldData data, UUID offender, double confidence) {
        Set<UUID> ids = new HashSet<>();
        for (CrimeReport report : data.reportsAgainst(offender)) {
            if (report.supportsArrest(confidence)) {
                ids.add(report.incidentId());
            }
        }
        return ids;
    }

    /** How many validated assignments of one role this community has. */
    public static int facilities(MinecraftServer server, CrimeCommunityKey community, FacilityRole role) {
        int count = 0;
        for (FacilityAssignment facility : CrimeFacilityService.list(server, role)) {
            if (!facility.ref().dimension().equals(community.dimension())) {
                continue;
            }
            // An unbound reference has no village id and cannot be attributed to a community; it still
            // exists and is still usable, it is simply not evidence about this village in particular.
            if (facility.ref().villageId() != community.villageId()) {
                continue;
            }
            count++;
        }
        return count;
    }

    private static boolean observationsEnabled() {
        try {
            return McaCrimeConfig.COMMON.enableObservations.get();
        } catch (Throwable t) {
            return false;
        }
    }

    private static double confidenceThreshold() {
        try {
            return McaCrimeConfig.COMMON.reportConfidenceThreshold.get();
        } catch (Throwable t) {
            return 1.0D;
        }
    }
}
