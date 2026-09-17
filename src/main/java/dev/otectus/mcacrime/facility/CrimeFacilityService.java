package dev.otectus.mcacrime.facility;

import dev.otectus.mcacrime.McaCrime;
import dev.otectus.mcacrime.McaCrimeConfig;
import dev.otectus.mcacrime.compat.TownsteadBridge;
import dev.otectus.mcacrime.compat.TownsteadBuildingView;
import dev.otectus.mcacrime.compat.TownsteadCapability;
import dev.otectus.mcacrime.jail.SafeCustodyDestination;
import dev.otectus.mcacrime.state.world.CrimeWorldData;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

import org.jetbrains.annotations.Nullable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * The registry of assigned civic facilities, and the one place a cell slot is handed out.
 *
 * <h2>What is stored and what is re-read</h2>
 *
 * <p>An assignment is a decision and is persisted. The building it points at is a reading and is never
 * persisted beyond the {@link TownsteadBuildingRef} needed to find it again: geometry, occupancy and
 * the village's own revision are all asked for fresh, because a settlement mod owns them and a second
 * copy would be a second truth. {@link #validate} is where the two meet, and its four outcomes are
 * deliberately not three:
 *
 * <ul>
 *   <li>{@code VALID} — the building is there and the village has not been restructured since.</li>
 *   <li>{@code STALE} — the building is there and the village revision has moved. Not a destruction:
 *       it is a demand that somebody look before a prisoner is sent.</li>
 *   <li>{@code MISSING} — the building genuinely is not registered any more.</li>
 *   <li>{@code UNVERIFIED} — nothing could be checked. An unloaded chunk, no Townstead, or an unbound
 *       enumeration capability. <b>This is not a failure</b>: a server with no settlement mod must
 *       still be able to run a jail, and "the chunk is asleep" is never proof that a building was
 *       demolished (§8.7).</li>
 * </ul>
 *
 * <h2>Reservations</h2>
 *
 * <p>A cell slot is taken before the escort starts walking and consumed on arrival, through a token.
 * Two arrests in the same tick therefore produce one winner and one refusal rather than two prisoners
 * in one cell. Everything about reservations is written against {@link CrimeWorldData} alone, with no
 * level and no server, which is what lets the simultaneous-arrest case be asserted in a unit test
 * rather than hoped for.
 */
public final class CrimeFacilityService {

    private CrimeFacilityService() {
    }

    /** How a stored assignment stands against the world right now. */
    public enum Status {
        /** The building is registered and the village revision matches. */
        VALID,
        /** Nothing could be checked; the assignment is usable and unconfirmed. */
        UNVERIFIED,
        /** The building is there, but the village has been restructured since it was assigned. */
        STALE,
        /** The building is no longer registered. */
        MISSING;

        /** Whether an arrest may be routed here. Stale and missing may not; unverified may. */
        public boolean usable() {
            return this == VALID || this == UNVERIFIED;
        }
    }

    /**
     * The outcome of one validation.
     *
     * @param status          how it stands
     * @param reason          one line for an operator; never empty
     * @param currentRevision the revision actually observed, or {@link TownsteadBuildingRef#UNKNOWN_REVISION}
     */
    public record Validation(Status status, String reason, int currentRevision) {

        public Validation {
            reason = reason == null || reason.isBlank() ? status.name().toLowerCase(Locale.ROOT) : reason;
        }

        static Validation of(Status status, String reason) {
            return new Validation(status, reason, TownsteadBuildingRef.UNKNOWN_REVISION);
        }

        public boolean usable() {
            return status.usable();
        }
    }

    /** What happened when a prisoner reached the cell they were reserved into. */
    public enum Arrival {
        /** The reservation was live, the destination re-checked, and the slot has been spent. */
        CONSUMED,
        /** There was no reservation. The ordinary case for an arrest that never used a facility. */
        NONE,
        /** The reservation had lapsed before the prisoner arrived; it has been released. */
        EXPIRED,
        /** The facility changed under the escort. The slot was released rather than spent. */
        INVALID
    }

    // --- validation -------------------------------------------------------------------------------

    /**
     * The validation rule itself, over nothing but a reference and what was read at its anchor.
     *
     * @param here                 the buildings found at the anchor, or {@code null} when the read
     *                             itself could not be made
     * @param enumerationAvailable whether {@link TownsteadCapability#BUILDING_ENUMERATION} is bound
     * @param loaded               whether the anchor's chunk is loaded, so absence means something
     */
    public static Validation validate(@Nullable TownsteadBuildingRef ref,
                                      @Nullable List<TownsteadBuildingView> here,
                                      boolean enumerationAvailable, boolean loaded) {
        if (ref == null) {
            return Validation.of(Status.MISSING, "the assignment has no building reference at all");
        }
        if (!ref.bound()) {
            return Validation.of(Status.UNVERIFIED,
                    "assigned without a recognised building, so there is nothing to re-check");
        }
        if (!enumerationAvailable) {
            return Validation.of(Status.UNVERIFIED,
                    "building enumeration is unavailable, so the building cannot be re-read");
        }
        if (!loaded) {
            // §8.7: an unloaded chunk is not proof of destruction, and treating it as one would delete
            // a village's jail every time nobody was standing in it.
            return Validation.of(Status.UNVERIFIED, "the chunk is not loaded, so absence proves nothing");
        }
        if (here == null) {
            return Validation.of(Status.UNVERIFIED, "the building read did not answer");
        }
        for (TownsteadBuildingView building : here) {
            if (!ref.names(building)) {
                continue;
            }
            if (!building.revisionKnown()) {
                return new Validation(Status.UNVERIFIED,
                        "the building is registered but carries no revision to compare",
                        TownsteadBuildingRef.UNKNOWN_REVISION);
            }
            if (ref.isStale(building.revision())) {
                return new Validation(Status.STALE,
                        "the village has been restructured since this facility was assigned (revision "
                                + ref.observedRevision() + " -> " + building.revision() + ")",
                        building.revision());
            }
            return new Validation(Status.VALID, "the building is registered and unchanged",
                    building.revision());
        }
        return Validation.of(Status.MISSING,
                "no registered building at this anchor matches the one this facility was assigned to");
    }

    /** The same, against a live level. */
    public static Validation validate(@Nullable ServerLevel level, @Nullable FacilityAssignment facility) {
        if (facility == null) {
            return Validation.of(Status.MISSING, "no such facility");
        }
        if (level == null || !level.dimension().location().equals(facility.ref().dimension())) {
            return Validation.of(Status.UNVERIFIED, "the facility is in another dimension");
        }
        boolean enumeration = TownsteadBridge.has(TownsteadCapability.BUILDING_ENUMERATION);
        boolean loaded = level.isLoaded(facility.anchor());
        List<TownsteadBuildingView> here = null;
        if (enumeration && loaded) {
            here = TownsteadBridge.buildingsAt(level, facility.anchor()).orElse(null);
        }
        return validate(facility.ref(), here, enumeration, loaded);
    }

    // --- assignment -------------------------------------------------------------------------------

    /**
     * Assigns a role to whatever building stands at {@code anchor}.
     *
     * <p>Assigning with no settlement mod present is allowed and produces an unbound reference: the
     * facility works, it is reported as unverified, and it never pretends a building was checked. That
     * is better than refusing, which would make an operator's jail depend on a companion mod being
     * installed.
     */
    public static Optional<FacilityAssignment> assign(@Nullable ServerLevel level, @Nullable FacilityRole role,
                                                      @Nullable BlockPos anchor, String assignedBy) {
        if (level == null || role == null || anchor == null) {
            return Optional.empty();
        }
        MinecraftServer server = level.getServer();
        if (server == null) {
            return Optional.empty();
        }
        TownsteadBuildingRef ref = referenceAt(level, anchor);
        FacilityAssignment assignment = FacilityAssignment.of(ref, role, anchor.immutable(), assignedBy,
                level.getGameTime());
        if (!CrimeWorldData.get(server).putFacility(assignment)) {
            return Optional.empty();
        }
        return Optional.of(assignment);
    }

    /**
     * What the datapack says a building at this position is for, if anything.
     *
     * <p>The half of facility assignment that does not need an operator. A settlement that ships a
     * guardhouse type, or a pack that declares one, should not need somebody to walk to every village
     * and run a command — {@code data/<ns>/townstead/building_roles/} says it once and this reads it.
     *
     * <p>A recognition is a <em>candidate</em> and never an assignment. It says what the building is
     * for; it says nothing about whether the anchor is somewhere a prisoner can stand, whether the
     * village has been restructured since, or whether an operator wanted it used. All of that is still
     * {@link #validate} and {@link #selectDestination}'s to decide, exactly as it is for a hand-made
     * assignment. Recognising a building has no effect at all until somebody or something acts on it.
     */
    public static Optional<dev.otectus.mcacrime.compat.TownsteadBuildingRoles.Recognition> recognise(
            @Nullable ServerLevel level, @Nullable BlockPos pos) {
        if (level == null || pos == null) {
            return Optional.empty();
        }
        return TownsteadBridge.buildingAt(level, pos).asOptional()
                .flatMap(building ->
                        dev.otectus.mcacrime.compat.TownsteadBuildingRoles.recognise(building.type()));
    }

    /**
     * Assigns whatever role the datapack recognises for the building at {@code anchor}.
     *
     * <p>The command form of {@link #recognise}: an operator who does not want to work out whether a
     * building is a guardhouse or a guard post asks the pack. Empty when nothing is recognised, which
     * is the common answer and is not a failure — it means this building type has no declared role and
     * the operator should name one.
     */
    public static Optional<FacilityAssignment> assignRecognised(@Nullable ServerLevel level,
                                                                @Nullable BlockPos anchor,
                                                                String assignedBy) {
        return recognise(level, anchor)
                .flatMap(recognition -> assign(level, recognition.role(), anchor, assignedBy));
    }

    /**
     * The reference for the building at a position, chosen deterministically.
     *
     * <p>Overlaps are real: a dock approach and the yard around it can both contain the same block. The
     * tightest building wins, and ties are broken by the lowest building id, so the same position always
     * produces the same reference and an operator re-running the command does not silently re-point
     * their jail at the other one (§8.2).
     */
    public static TownsteadBuildingRef referenceAt(ServerLevel level, BlockPos pos) {
        List<TownsteadBuildingView> here = TownsteadBridge.buildingsAt(level, pos).orElse(List.of());
        return here.stream()
                .min(Comparator.<TownsteadBuildingView>comparingLong(CrimeFacilityService::volumeOf)
                        .thenComparingInt(TownsteadBuildingView::id))
                .map(building -> TownsteadBuildingRef.of(level.dimension().location(), building))
                // With only the single-position facade, the building can still be named -- it just
                // arrives without a revision, so the reference is bound and uncomparable rather than
                // unbound. Recording which building an operator pointed at is worth having even when
                // nothing can re-check it later.
                .or(() -> TownsteadBridge.buildingAt(level, pos).asOptional()
                        .map(building -> TownsteadBuildingRef.of(level.dimension().location(), building)))
                .orElseGet(() -> TownsteadBuildingRef.unbound(level.dimension().location()));
    }

    private static long volumeOf(TownsteadBuildingView building) {
        long dx = (long) building.maxX() - building.minX() + 1L;
        long dy = (long) building.maxY() - building.minY() + 1L;
        long dz = (long) building.maxZ() - building.minZ() + 1L;
        return dx * dy * dz;
    }

    public static List<FacilityAssignment> list(@Nullable MinecraftServer server, @Nullable FacilityRole role) {
        return server == null ? List.of() : CrimeWorldData.get(server).facilities(role);
    }

    /** Resolves a full or abbreviated assignment id, as {@code /crime facility} prints it. */
    public static Optional<FacilityAssignment> byId(@Nullable MinecraftServer server, String id) {
        if (server == null || id == null || id.isBlank()) {
            return Optional.empty();
        }
        String needle = id.trim().toLowerCase(Locale.ROOT);
        List<FacilityAssignment> matches = new ArrayList<>();
        for (FacilityAssignment facility : CrimeWorldData.get(server).facilities()) {
            if (facility.id().toString().toLowerCase(Locale.ROOT).startsWith(needle)) {
                matches.add(facility);
            }
        }
        // An ambiguous abbreviation resolves to nothing rather than to the first match: removing the
        // wrong jail because two ids shared four characters is not a recoverable mistake.
        return matches.size() == 1 ? Optional.of(matches.get(0)) : Optional.empty();
    }

    public static boolean remove(@Nullable MinecraftServer server, @Nullable UUID id) {
        return server != null && id != null && CrimeWorldData.get(server).removeFacility(id);
    }

    // --- destination selection --------------------------------------------------------------------

    /**
     * The nearest usable facility of this role within the configured radius.
     *
     * <p>Bounded, always. The unlimited lookup belongs to an operator running a command
     * ({@code JailRegistry.nearestTo(player)}); an automatic arrest that could reach any facility in
     * the dimension would teleport prisoners across the map, which is the behaviour the bounded ladder
     * exists to prevent.
     */
    public static Optional<FacilityAssignment> selectDestination(@Nullable ServerLevel level,
                                                                 @Nullable FacilityRole role,
                                                                 @Nullable BlockPos from) {
        return selectDestination(level, role, from, searchRadius());
    }

    /** The same, with an explicit radius, so a caller with its own ceiling is not forced through config. */
    public static Optional<FacilityAssignment> selectDestination(@Nullable ServerLevel level,
                                                                 @Nullable FacilityRole role,
                                                                 @Nullable BlockPos from,
                                                                 int radius) {
        MinecraftServer server = level == null ? null : level.getServer();
        if (server == null || role == null || from == null) {
            return Optional.empty();
        }
        long ceiling = (long) radius * radius;
        List<FacilityAssignment> candidates = new ArrayList<>();
        for (FacilityAssignment facility : CrimeWorldData.get(server).facilities(role)) {
            if (!facility.ref().dimension().equals(level.dimension().location())) {
                continue;
            }
            if (radius > 0 && facility.anchor().distSqr(from) > ceiling) {
                continue;
            }
            candidates.add(facility);
        }
        candidates.sort(Comparator.comparingDouble(facility -> facility.anchor().distSqr(from)));
        for (FacilityAssignment facility : candidates) {
            if (validate(level, facility).usable() && standable(level, facility)) {
                return Optional.of(facility);
            }
        }
        return Optional.empty();
    }

    /**
     * Whether a prisoner could actually be put down at this facility.
     *
     * <p>Only asked while the anchor's chunk is loaded. An unloaded jail is accepted on exactly the
     * reasoning {@link #validate} uses for a missing building: the server does not know what is there,
     * and "I cannot see it" must not become "it is unusable" — otherwise a village's jail would be
     * skipped every time nobody was standing in it, and a cage would be dug beside it instead.
     */
    private static boolean standable(ServerLevel level, FacilityAssignment facility) {
        if (!level.isLoaded(facility.anchor())) {
            return true;
        }
        return SafeCustodyDestination.validate(level, facility.anchor(), 4).isPresent();
    }

    private static int searchRadius() {
        try {
            return McaCrimeConfig.COMMON.townsteadFacilitySearchRadius.get();
        } catch (Throwable t) {
            return 96; // no config loaded (a unit test, or a very early call)
        }
    }

    // --- reservations -----------------------------------------------------------------------------

    /**
     * Takes one slot in {@code facility} for {@code prisoner}, or refuses.
     *
     * <p>The capacity check and the write happen together, under the store, which is what makes two
     * arrests in one tick produce one winner. A prisoner who already holds a live reservation gets the
     * same one back rather than a second slot: re-running an arrest must not consume the village's
     * cells one at a time.
     */
    public static Optional<CellReservation> reserve(@Nullable CrimeWorldData data,
                                                    @Nullable FacilityAssignment facility,
                                                    @Nullable UUID prisoner, long now, long leaseTicks) {
        if (data == null || facility == null || prisoner == null || !facility.holdsPrisoners()) {
            return Optional.empty();
        }
        CellReservation existing = data.cellReservationForPrisoner(prisoner, now);
        if (existing != null) {
            return existing.facilityId().equals(facility.id()) ? Optional.of(existing) : Optional.empty();
        }
        List<CellReservation> live = data.cellReservationsFor(facility.id(), now);
        if (live.size() >= facility.capacity()) {
            return Optional.empty();
        }
        int slot = firstFreeSlot(live, facility.capacity());
        if (slot < 0) {
            return Optional.empty();
        }
        long lease = leaseTicks <= 0L ? CellReservation.DEFAULT_LEASE_TICKS : leaseTicks;
        CellReservation reservation = new CellReservation(UUID.randomUUID(), facility.id(), slot, prisoner,
                now, now + lease);
        return data.putCellReservation(reservation) ? Optional.of(reservation) : Optional.empty();
    }

    /** The lowest slot index no live reservation is using. */
    private static int firstFreeSlot(List<CellReservation> live, int capacity) {
        for (int slot = 0; slot < capacity; slot++) {
            boolean taken = false;
            for (CellReservation reservation : live) {
                if (reservation.slot() == slot) {
                    taken = true;
                    break;
                }
            }
            if (!taken) {
                return slot;
            }
        }
        return -1;
    }

    /**
     * Spends a reservation. The slot is free afterwards because the prisoner is now in the cell rather
     * than on their way to it.
     *
     * @return false when the token names nothing, which is what a double-consume looks like
     */
    public static boolean consume(@Nullable CrimeWorldData data, @Nullable UUID token) {
        return data != null && token != null && data.removeCellReservation(token);
    }

    /** Gives a reservation back unspent. Identical in effect to {@link #consume}, opposite in meaning. */
    public static boolean release(@Nullable CrimeWorldData data, @Nullable UUID token) {
        return data != null && token != null && data.removeCellReservation(token);
    }

    /** The live reservation held for a prisoner, if any. */
    public static Optional<CellReservation> reservationFor(@Nullable CrimeWorldData data,
                                                           @Nullable UUID prisoner, long now) {
        return data == null || prisoner == null
                ? Optional.empty()
                : Optional.ofNullable(data.cellReservationForPrisoner(prisoner, now));
    }

    /**
     * Releases whatever a prisoner was holding, by prisoner rather than by token.
     *
     * <p>Every abort path needs this and none of them has the token: an arrest that fails after
     * reserving, an escort whose prisoner logged out, a sentence that ended somewhere else. Releasing
     * exactly once is the requirement (§8.7), and going through the store means a second call finds
     * nothing and does nothing.
     */
    public static boolean releaseFor(@Nullable MinecraftServer server, @Nullable UUID prisoner) {
        if (server == null || prisoner == null) {
            return false;
        }
        CrimeWorldData data = CrimeWorldData.get(server);
        // Deliberately not filtered by liveness: an expired lease is still a row in the store, and an
        // abort path is exactly where it should be cleared rather than left for the sweep.
        boolean released = false;
        for (CellReservation reservation : data.cellReservations()) {
            if (reservation.prisoner().equals(prisoner)) {
                released |= data.removeCellReservation(reservation.token());
            }
        }
        return released;
    }

    /**
     * Revalidates a prisoner's destination at the moment they reach it, and spends the reservation.
     *
     * <p>Arrival is the last point at which a wrong destination is still cheap. A facility that was
     * demolished, restructured, or whose lease ran out during a long walk must not quietly become the
     * place a sentence is served; the reservation is given back instead, and the caller falls through
     * to its own ladder.
     */
    public static Arrival arrive(@Nullable ServerLevel level, @Nullable UUID prisoner) {
        MinecraftServer server = level == null ? null : level.getServer();
        if (server == null || prisoner == null) {
            return Arrival.NONE;
        }
        CrimeWorldData data = CrimeWorldData.get(server);
        long now = level.getGameTime();
        CellReservation reservation = data.cellReservationForPrisoner(prisoner, now);
        if (reservation == null) {
            // Either none was ever taken, or the lease ran out. The second case still leaves a row.
            return releaseFor(server, prisoner) ? Arrival.EXPIRED : Arrival.NONE;
        }
        FacilityAssignment facility = data.facility(reservation.facilityId());
        Validation validation = validate(level, facility);
        if (facility == null || !validation.usable()) {
            data.removeCellReservation(reservation.token());
            McaCrime.LOGGER.debug("MCA: Crime released a cell reservation on arrival: {}",
                    validation.reason());
            return Arrival.INVALID;
        }
        // A validation that read a revision re-stamps the assignment, so the next check compares against
        // what was actually seen rather than against a reading from an older session.
        if (validation.currentRevision() >= 0) {
            data.putFacility(facility.revalidatedAt(validation.currentRevision()));
        }
        data.removeCellReservation(reservation.token());
        return Arrival.CONSUMED;
    }

    /** Drops lapsed reservations. Called from the enforcement sweep, which is already throttled. */
    public static void sweep(@Nullable MinecraftServer server) {
        if (server == null) {
            return;
        }
        int dropped = CrimeWorldData.get(server).pruneCellReservations(server.overworld().getGameTime());
        if (dropped > 0) {
            McaCrime.LOGGER.debug("MCA: Crime released {} lapsed cell reservation(s)", dropped);
        }
    }

    /** One report per assignment, for {@code /crime facility list} and {@code validate}. */
    public static List<String> report(@Nullable ServerLevel level, @Nullable FacilityRole role) {
        MinecraftServer server = level == null ? null : level.getServer();
        List<String> lines = new ArrayList<>();
        if (server == null) {
            return lines;
        }
        CrimeWorldData data = CrimeWorldData.get(server);
        long now = level.getGameTime();
        for (FacilityAssignment facility : data.facilities(role)) {
            Validation validation = validate(level, facility);
            int reserved = data.cellReservationsFor(facility.id(), now).size();
            lines.add(facility.describe() + " — " + validation.status().name().toLowerCase(Locale.ROOT)
                    + ": " + validation.reason()
                    + (facility.holdsPrisoners() ? " [" + reserved + "/" + facility.capacity()
                            + " reserved]" : ""));
        }
        if (lines.isEmpty()) {
            lines.add("No facilities are assigned"
                    + (role == null ? "." : " with role " + role.id() + "."));
        }
        return lines;
    }

    /** Whether the enumeration behind facility validation is actually live. */
    public static boolean enumerationAvailable() {
        return TownsteadBridge.has(TownsteadCapability.BUILDING_ENUMERATION);
    }
}
