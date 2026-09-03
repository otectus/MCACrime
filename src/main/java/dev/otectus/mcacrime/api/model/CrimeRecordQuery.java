package dev.otectus.mcacrime.api.model;

import dev.otectus.mcacrime.ledger.Resolution;
import net.minecraft.resources.ResourceLocation;

import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * A bounded filter over a player's crime record. Every field is optional; an empty query matches
 * everything up to {@link #limit()}.
 *
 * <p><b>The canonical constructor clamps rather than throws.</b> This type is built straight from a
 * decoded packet, and a throw inside a decoder drops the connection — a hostile client should not be
 * able to disconnect itself into a confusing error, and an honest client with a slightly-too-large
 * page request should just get a smaller page. Bounds are enforced here so no call site has to
 * remember them.
 *
 * <p>Queries are always scoped to a single offender by the caller; there is no offender field,
 * which is what structurally prevents one player enumerating another's history.
 */
public record CrimeRecordQuery(
        Set<ResourceLocation> crimeTypes,
        Set<Resolution> resolutions,
        Optional<Boolean> witnessed,
        Optional<UUID> victimId,
        Optional<CrimeCommunityKey> community,
        long maxAgeTicks,
        int limit,
        SortOrder order) {

    /** Sort direction. Ties always break on record UUID, so ordering is total and reproducible. */
    public enum SortOrder {
        NEWEST_FIRST,
        OLDEST_FIRST
    }

    /** The largest page any caller may ask for. */
    public static final int MAX_LIMIT = 100;
    /** How many entries a filter set may hold before the rest are dropped. */
    public static final int MAX_FILTER_ENTRIES = 16;
    /** The default page size when a caller does not care. */
    public static final int DEFAULT_LIMIT = 25;

    /** Everything, newest first, one default page. */
    public static final CrimeRecordQuery ANY = builder().build();

    /** The cases a guard would still act on. {@code ESCAPED} is not forgiveness. */
    public static final Set<Resolution> ACTIONABLE = Set.of(Resolution.UNRESOLVED, Resolution.ESCAPED);

    public CrimeRecordQuery {
        crimeTypes = bound(crimeTypes);
        resolutions = bound(resolutions);
        witnessed = witnessed == null ? Optional.empty() : witnessed;
        victimId = victimId == null ? Optional.empty() : victimId;
        community = community == null ? Optional.empty() : community;
        maxAgeTicks = Math.max(0L, maxAgeTicks);
        limit = Math.max(1, Math.min(MAX_LIMIT, limit));
        order = order == null ? SortOrder.NEWEST_FIRST : order;
    }

    /** Copies at most {@link #MAX_FILTER_ENTRIES}, preserving encounter order for determinism. */
    private static <T> Set<T> bound(Set<T> raw) {
        if (raw == null || raw.isEmpty()) {
            return Set.of();
        }
        if (raw.size() <= MAX_FILTER_ENTRIES) {
            return Set.copyOf(raw);
        }
        Set<T> trimmed = new LinkedHashSet<>(MAX_FILTER_ENTRIES);
        for (T value : raw) {
            if (trimmed.size() == MAX_FILTER_ENTRIES) {
                break;
            }
            trimmed.add(value);
        }
        return Set.copyOf(trimmed);
    }

    /** Whether {@code maxAgeTicks} constrains anything. Zero means "any age". */
    public boolean bounded() {
        return maxAgeTicks > 0L;
    }

    /** Whether {@code view} satisfies every present filter, given the current game time. */
    public boolean matches(CrimeRecordView view, long now) {
        if (!crimeTypes.isEmpty() && !crimeTypes.contains(view.crimeType())) {
            return false;
        }
        if (!resolutions.isEmpty() && !resolutions.contains(view.resolution())) {
            return false;
        }
        if (witnessed.isPresent() && witnessed.get() != view.witnessed()) {
            return false;
        }
        if (victimId.isPresent() && !victimId.equals(view.victimId())) {
            return false;
        }
        if (community.isPresent() && !community.equals(view.community())) {
            return false;
        }
        return !bounded() || now - view.committedGameTime() <= maxAgeTicks;
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Mutable builder; every setter tolerates null and the result is clamped by the record. */
    public static final class Builder {

        private Set<ResourceLocation> crimeTypes = Set.of();
        private Set<Resolution> resolutions = Set.of();
        private Optional<Boolean> witnessed = Optional.empty();
        private Optional<UUID> victimId = Optional.empty();
        private Optional<CrimeCommunityKey> community = Optional.empty();
        private long maxAgeTicks;
        private int limit = DEFAULT_LIMIT;
        private SortOrder order = SortOrder.NEWEST_FIRST;

        private Builder() {
        }

        public Builder crimeTypes(Set<ResourceLocation> types) {
            this.crimeTypes = types == null ? Set.of() : types;
            return this;
        }

        public Builder resolutions(Set<Resolution> resolutions) {
            this.resolutions = resolutions == null ? Set.of() : resolutions;
            return this;
        }

        /** Shorthand for the {@code UNRESOLVED} + {@code ESCAPED} set a guard cares about. */
        public Builder actionableOnly() {
            return resolutions(ACTIONABLE);
        }

        public Builder witnessed(boolean witnessed) {
            this.witnessed = Optional.of(witnessed);
            return this;
        }

        public Builder victim(UUID victimId) {
            this.victimId = Optional.ofNullable(victimId);
            return this;
        }

        public Builder community(CrimeCommunityKey community) {
            this.community = Optional.ofNullable(community);
            return this;
        }

        public Builder maxAgeTicks(long maxAgeTicks) {
            this.maxAgeTicks = maxAgeTicks;
            return this;
        }

        public Builder limit(int limit) {
            this.limit = limit;
            return this;
        }

        public Builder order(SortOrder order) {
            this.order = order;
            return this;
        }

        public CrimeRecordQuery build() {
            return new CrimeRecordQuery(crimeTypes, resolutions, witnessed, victimId, community,
                    maxAgeTicks, limit, order);
        }
    }
}
