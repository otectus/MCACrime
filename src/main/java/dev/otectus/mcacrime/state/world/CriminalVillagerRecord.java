package dev.otectus.mcacrime.state.world;

import dev.otectus.mcacrime.job.CriminalJob;
import dev.otectus.mcacrime.job.HistoricalProfessionKind;
import dev.otectus.mcacrime.job.OccupationSource;
import dev.otectus.mcacrime.job.OccupationStatus;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;

import javax.annotation.Nullable;

import java.util.Set;
import java.util.UUID;

/**
 * One villager's criminal occupation, as persisted (0.5.1; occupational state added in 0.7.2).
 *
 * <p>This is authoritative, not MCA's profession — but as of 0.7.2 the two are no longer allowed to
 * disagree for a Thief, so this record carries the state machine that keeps them in step: which
 * lifecycle state the occupation is in, which route created it, where its station is, and when it
 * earned the establishment milestone.
 *
 * <h2>Previous profession, said out loud</h2>
 *
 * <p>{@code previousProfessionId} used to encode three meanings in one nullable string: {@code null}
 * for "nothing was displaced", {@code ""} for "there was something and we could not read it", and an
 * id for the real answer. {@link #previousProfessionKind()} now says which of the three it is, because
 * a rollback that cannot tell the middle case from the first will invent an unemployed villager where
 * a cleric used to be.
 *
 * <h2>Unknown keys survive</h2>
 *
 * <p>{@link #extra()} carries every key this build did not recognise straight back out again, so a
 * world touched by a newer MCA: Crime and then opened by this one does not lose the newer build's
 * per-villager state. It is the same forward-compatibility promise {@code CrimeWorldData}'s reserved
 * tag makes for the file as a whole, applied per record.
 */
public record CriminalVillagerRecord(UUID villager, CriminalJob job, long assignedDay, long lastMugAt,
                                     long lastSeenDay, boolean wildOrigin, long personalitySeed,
                                     @Nullable String previousProfessionId,
                                     HistoricalProfessionKind previousProfessionKind,
                                     OccupationStatus status, OccupationSource source,
                                     long establishedAt, long lastVisitAt, long employedTicks,
                                     @Nullable WorksiteRef worksite,
                                     @Nullable WorksiteRef reservation, long reservationAt,
                                     long unboundSince,
                                     CompoundTag extra) {

    /** Keys this build owns. Anything else in a saved record tag is round-tripped through {@link #extra()}. */
    private static final Set<String> KNOWN_KEYS = Set.of(
            "villager", "job", "assignedDay", "lastMugAt", "lastSeenDay", "wildOrigin", "personalitySeed",
            "previousProfessionId", "previousProfessionKind", "status", "source", "establishedAt",
            "lastVisitAt", "employedTicks", "worksite", "reservation", "reservationAt", "unboundSince");

    public CriminalVillagerRecord {
        job = job == null ? CriminalJob.NONE : job;
        previousProfessionKind = previousProfessionKind == null
                ? HistoricalProfessionKind.ofLegacy(previousProfessionId) : previousProfessionKind;
        status = status == null ? OccupationStatus.NONE : status;
        source = source == null ? OccupationSource.UNKNOWN : source;
        extra = extra == null ? new CompoundTag() : extra;
    }

    /**
     * A brand-new record for a job that has not yet been through the occupation transaction.
     *
     * <p>Fence keeps the pre-0.7.2 shape: {@link OccupationStatus#NONE}, because occupational
     * exclusivity is specifically a Thief rule (spec §9.5) and giving a fence a lifecycle state would
     * be exactly the "turn every historical criminal label into a compulsory native profession"
     * mistake that section forbids.
     */
    public static CriminalVillagerRecord fresh(UUID villager, CriminalJob job, long day, boolean wildOrigin,
                                               long personalitySeed, OccupationSource source) {
        return new CriminalVillagerRecord(villager, job, day, 0L, day, wildOrigin, personalitySeed,
                null, HistoricalProfessionKind.NONE,
                job == CriminalJob.THIEF ? OccupationStatus.PENDING : OccupationStatus.NONE,
                source, 0L, 0L, 0L, null, null, 0L, 0L, new CompoundTag());
    }

    /**
     * A record in its pre-occupation shape: identity, timings and a remembered profession, with no
     * lifecycle state.
     *
     * <p>Exactly what a schema-11 world holds, and what a Fence still holds, so the eight values that
     * meant something before 0.7.2 can be written without naming ten that do not apply.
     */
    public static CriminalVillagerRecord of(UUID villager, CriminalJob job, long assignedDay, long lastMugAt,
                                            long lastSeenDay, boolean wildOrigin, long personalitySeed,
                                            @Nullable String previousProfessionId) {
        return new CriminalVillagerRecord(villager, job, assignedDay, lastMugAt, lastSeenDay, wildOrigin,
                personalitySeed, previousProfessionId,
                HistoricalProfessionKind.ofLegacy(previousProfessionId),
                OccupationStatus.NONE, OccupationSource.UNKNOWN, 0L, 0L, 0L, null, null, 0L, 0L,
                new CompoundTag());
    }

    // --- field-preserving updates ----------------------------------------------------------------
    //
    // Every one of these replaces a hand-written `new CriminalVillagerRecord(existing.a(), existing.b(),
    // ...)` at a call site. Those reconstructions were correct when the record had eight components and
    // became a standing hazard at eighteen: adding a field meant finding every literal constructor call
    // and threading it through, and the one that was missed silently reset that field to its default.

    public CriminalVillagerRecord withJob(CriminalJob newJob) {
        return new CriminalVillagerRecord(villager, newJob, assignedDay, lastMugAt, lastSeenDay, wildOrigin,
                personalitySeed, previousProfessionId, previousProfessionKind, status, source, establishedAt,
                lastVisitAt, employedTicks, worksite, reservation, reservationAt, unboundSince, extra);
    }

    public CriminalVillagerRecord withLastMugAt(long now) {
        return new CriminalVillagerRecord(villager, job, assignedDay, now, lastSeenDay, wildOrigin,
                personalitySeed, previousProfessionId, previousProfessionKind, status, source, establishedAt,
                lastVisitAt, employedTicks, worksite, reservation, reservationAt, unboundSince, extra);
    }

    public CriminalVillagerRecord withLastSeenDay(long day) {
        return new CriminalVillagerRecord(villager, job, assignedDay, lastMugAt, day, wildOrigin,
                personalitySeed, previousProfessionId, previousProfessionKind, status, source, establishedAt,
                lastVisitAt, employedTicks, worksite, reservation, reservationAt, unboundSince, extra);
    }

    public CriminalVillagerRecord withPreviousProfession(HistoricalProfessionKind kind, @Nullable String id) {
        return new CriminalVillagerRecord(villager, job, assignedDay, lastMugAt, lastSeenDay, wildOrigin,
                personalitySeed, kind == HistoricalProfessionKind.ID ? id : null, kind, status, source,
                establishedAt, lastVisitAt, employedTicks, worksite, reservation, reservationAt, unboundSince,
                extra);
    }

    public CriminalVillagerRecord withStatus(OccupationStatus newStatus) {
        return new CriminalVillagerRecord(villager, job, assignedDay, lastMugAt, lastSeenDay, wildOrigin,
                personalitySeed, previousProfessionId, previousProfessionKind, newStatus, source, establishedAt,
                lastVisitAt, employedTicks, worksite, reservation, reservationAt, unboundSince, extra);
    }

    public CriminalVillagerRecord withSource(OccupationSource newSource) {
        return new CriminalVillagerRecord(villager, job, assignedDay, lastMugAt, lastSeenDay, wildOrigin,
                personalitySeed, previousProfessionId, previousProfessionKind, status, newSource, establishedAt,
                lastVisitAt, employedTicks, worksite, reservation, reservationAt, unboundSince, extra);
    }

    /** Binds the occupation to a station, clearing any pending reservation and the unbound clock. */
    public CriminalVillagerRecord withWorksite(@Nullable WorksiteRef site) {
        return new CriminalVillagerRecord(villager, job, assignedDay, lastMugAt, lastSeenDay, wildOrigin,
                personalitySeed, previousProfessionId, previousProfessionKind, status, source, establishedAt,
                lastVisitAt, employedTicks, site, site == null ? reservation : null,
                site == null ? reservationAt : 0L, site == null ? unboundSince : 0L, extra);
    }

    /**
     * Records a reservation that is being walked to.
     *
     * <p>Held here rather than in the villager's {@code POTENTIAL_JOB_SITE} memory on purpose: the
     * acquisition boundary filters Mask Station out of every non-Thief acquirable predicate, so
     * vanilla's own potential-site validator would reject the memory and release the ticket the
     * approach still depends on.
     */
    public CriminalVillagerRecord withReservation(@Nullable WorksiteRef site, long now) {
        return new CriminalVillagerRecord(villager, job, assignedDay, lastMugAt, lastSeenDay, wildOrigin,
                personalitySeed, previousProfessionId, previousProfessionKind, status, source, establishedAt,
                lastVisitAt, employedTicks, worksite, site, site == null ? 0L : now, unboundSince, extra);
    }

    public CriminalVillagerRecord withUnboundSince(long now) {
        return new CriminalVillagerRecord(villager, job, assignedDay, lastMugAt, lastSeenDay, wildOrigin,
                personalitySeed, previousProfessionId, previousProfessionKind, status, source, establishedAt,
                lastVisitAt, employedTicks, worksite, reservation, reservationAt, now, extra);
    }

    public CriminalVillagerRecord withVisit(long now) {
        return new CriminalVillagerRecord(villager, job, assignedDay, lastMugAt, lastSeenDay, wildOrigin,
                personalitySeed, previousProfessionId, previousProfessionKind, status, source, establishedAt,
                now, employedTicks, worksite, reservation, reservationAt, unboundSince, extra);
    }

    public CriminalVillagerRecord withEmployedTicks(long ticks) {
        return new CriminalVillagerRecord(villager, job, assignedDay, lastMugAt, lastSeenDay, wildOrigin,
                personalitySeed, previousProfessionId, previousProfessionKind, status, source, establishedAt,
                lastVisitAt, Math.max(0L, ticks), worksite, reservation, reservationAt, unboundSince, extra);
    }

    public CriminalVillagerRecord withEstablishedAt(long now) {
        return new CriminalVillagerRecord(villager, job, assignedDay, lastMugAt, lastSeenDay, wildOrigin,
                personalitySeed, previousProfessionId, previousProfessionKind, status, source, now,
                lastVisitAt, employedTicks, worksite, reservation, reservationAt, unboundSince, extra);
    }

    /**
     * Retires the occupation without discarding what it accumulated (spec §10.4).
     *
     * <p>The job becomes {@link CriminalJob#NONE} and the station goes, but {@code lastMugAt},
     * {@code assignedDay}, the personality seed and the remembered previous profession stay: dropping
     * them with the role would reset the per-thief mug cooldown, which is a reward for being fired.
     */
    public CriminalVillagerRecord retired() {
        return new CriminalVillagerRecord(villager, CriminalJob.NONE, assignedDay, lastMugAt, lastSeenDay,
                wildOrigin, personalitySeed, previousProfessionId, previousProfessionKind,
                OccupationStatus.RETIRED, source, establishedAt, lastVisitAt, employedTicks,
                null, null, 0L, unboundSince, extra);
    }

    // --- persistence -----------------------------------------------------------------------------

    public CompoundTag save() {
        CompoundTag tag = extra.copy(); // unknown keys first, so a known key always wins
        tag.putUUID("villager", villager);
        tag.putString("job", job.id());
        tag.putLong("assignedDay", assignedDay);
        tag.putLong("lastMugAt", lastMugAt);
        tag.putLong("lastSeenDay", lastSeenDay);
        tag.putBoolean("wildOrigin", wildOrigin);
        tag.putLong("personalitySeed", personalitySeed);
        if (previousProfessionId != null && !previousProfessionId.isBlank()) {
            tag.putString("previousProfessionId", previousProfessionId);
        }
        tag.putString("previousProfessionKind", previousProfessionKind.id());
        tag.putString("status", status.id());
        tag.putString("source", source.id());
        tag.putLong("establishedAt", establishedAt);
        tag.putLong("lastVisitAt", lastVisitAt);
        tag.putLong("employedTicks", employedTicks);
        putPos(tag, "worksite", worksite);
        putPos(tag, "reservation", reservation);
        tag.putLong("reservationAt", reservationAt);
        tag.putLong("unboundSince", unboundSince);
        return tag;
    }

    /** Throws on a tag with no villager id; the caller skips that one entry. */
    public static CriminalVillagerRecord load(CompoundTag tag) {
        String previous = tag.contains("previousProfessionId") ? tag.getString("previousProfessionId") : null;
        HistoricalProfessionKind kind = tag.contains("previousProfessionKind")
                ? HistoricalProfessionKind.parse(tag.getString("previousProfessionKind"))
                : HistoricalProfessionKind.ofLegacy(previous);
        CompoundTag extra = new CompoundTag();
        for (String key : tag.getAllKeys()) {
            if (!KNOWN_KEYS.contains(key)) {
                extra.put(key, tag.get(key).copy());
            }
        }
        return new CriminalVillagerRecord(
                tag.getUUID("villager"),
                CriminalJob.parse(tag.getString("job")),
                tag.getLong("assignedDay"),
                tag.getLong("lastMugAt"),
                tag.getLong("lastSeenDay"),
                tag.getBoolean("wildOrigin"),
                tag.getLong("personalitySeed"),
                kind == HistoricalProfessionKind.ID ? previous : null,
                kind,
                OccupationStatus.parse(tag.getString("status")),
                OccupationSource.parse(tag.getString("source")),
                tag.getLong("establishedAt"),
                tag.getLong("lastVisitAt"),
                tag.getLong("employedTicks"),
                readPos(tag, "worksite"),
                readPos(tag, "reservation"),
                tag.getLong("reservationAt"),
                tag.getLong("unboundSince"),
                extra);
    }

    /**
     * Dimension plus position, written long-hand.
     *
     * <p>Not {@code GlobalPos.CODEC}: a codec failure on load would drop the whole record, and a
     * station whose dimension was renamed or removed by a datapack must read back as "a site in a
     * dimension that is not here" rather than as a decoding error.
     */
    private static void putPos(CompoundTag tag, String key, @Nullable WorksiteRef site) {
        if (site == null) {
            tag.remove(key);
        } else {
            tag.put(key, site.save());
        }
    }

    @Nullable
    private static WorksiteRef readPos(CompoundTag tag, String key) {
        return tag.contains(key, net.minecraft.nbt.Tag.TAG_COMPOUND)
                ? WorksiteRef.load(tag.getCompound(key))
                : null;
    }
}
