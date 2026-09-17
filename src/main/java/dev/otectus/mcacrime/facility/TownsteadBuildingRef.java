package dev.otectus.mcacrime.facility;

import dev.otectus.mcacrime.compat.TownsteadBuildingView;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;

import javax.annotation.Nullable;

/**
 * A durable pointer at one settlement building: which dimension, which village, which building, and
 * what the village's revision was when MCA: Crime last looked.
 *
 * <p><b>The revision is the whole point.</b> A jail assignment outlives the session it was made in,
 * and between two sessions a village can merge, split, be deleted, or reuse a building id for
 * something else entirely. Storing only "village 3, building 7" would let a sentence quietly move into
 * a stranger's kitchen. Storing the revision the reference was taken at means the same three numbers
 * can be <em>compared</em> later: an unchanged revision is proof nothing was restructured, and a
 * changed one is a demand for revalidation rather than a silent re-attachment.
 *
 * <p>Three states, not two. A reference may be fresh, stale, or <em>uncomparable</em> — the last when
 * no revision was ever observed, because Townstead was absent or the enumeration capability was not
 * bound when the facility was assigned. Uncomparable must never read as fresh: the correct answer for
 * a reference nobody can check is "unverified", which is what {@link CrimeFacilityService} reports.
 *
 * @param dimension       the level the building is in; never inferred from where a command was run
 * @param villageId       MCA's own village id, the same integer {@code CrimeCommunityKey} uses
 * @param buildingId      the building id within that village
 * @param observedRevision the village revision at the moment the reference was taken, or
 *                        {@link #UNKNOWN_REVISION}
 */
public record TownsteadBuildingRef(ResourceLocation dimension, int villageId, int buildingId,
                                   int observedRevision) {

    /** No revision was ever read, so this reference cannot be compared against a later one. */
    public static final int UNKNOWN_REVISION = -1;

    /** The village id of a reference that names no village at all. */
    public static final int NO_VILLAGE = -1;

    public TownsteadBuildingRef {
        if (dimension == null) {
            throw new IllegalArgumentException("a building reference must name a dimension");
        }
        villageId = villageId < 0 ? NO_VILLAGE : villageId;
        buildingId = buildingId < 0 ? NO_VILLAGE : buildingId;
        observedRevision = observedRevision < 0 ? UNKNOWN_REVISION : observedRevision;
    }

    /**
     * A reference to a place no settlement mod recognised.
     *
     * <p>Not a failure: an operator on a server with no Townstead may still assign a guard post, and
     * refusing would make the whole facility layer depend on a companion mod. It simply cannot be
     * revalidated, and says so.
     */
    public static TownsteadBuildingRef unbound(ResourceLocation dimension) {
        return new TownsteadBuildingRef(dimension, NO_VILLAGE, NO_VILLAGE, UNKNOWN_REVISION);
    }

    /** The reference for a building MCA: Crime has just read, carrying whatever revision came with it. */
    public static TownsteadBuildingRef of(ResourceLocation dimension, TownsteadBuildingView building) {
        return new TownsteadBuildingRef(dimension, building.villageId(), building.id(),
                building.revisionKnown() ? building.revision() : UNKNOWN_REVISION);
    }

    /** Whether this reference names a building at all, as opposed to a bare dimension. */
    public boolean bound() {
        return villageId != NO_VILLAGE && buildingId != NO_VILLAGE;
    }

    /** Whether a later revision can be compared against this one. */
    public boolean comparable() {
        return bound() && observedRevision != UNKNOWN_REVISION;
    }

    /**
     * Whether the village has been restructured since this reference was taken.
     *
     * <p>False for a reference that cannot be compared, and that is deliberate rather than optimistic:
     * "not stale" here means "this check found no evidence of change", and the caller is expected to
     * ask {@link #comparable()} before treating that as reassurance. A reference that reported itself
     * stale merely because nobody had recorded a revision would invalidate every facility on a server
     * that has no Townstead at all.
     */
    public boolean isStale(int currentRevision) {
        return comparable() && currentRevision >= 0 && currentRevision != observedRevision;
    }

    /** Whether a building MCA: Crime has just read is the one this reference names. */
    public boolean names(@Nullable TownsteadBuildingView building) {
        return building != null && bound()
                && building.villageId() == villageId && building.id() == buildingId;
    }

    /** The same reference, re-stamped after a successful revalidation. */
    public TownsteadBuildingRef observedAt(int revision) {
        return revision == observedRevision ? this
                : new TownsteadBuildingRef(dimension, villageId, buildingId, revision);
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putString("dim", dimension.toString());
        tag.putInt("village", villageId);
        tag.putInt("building", buildingId);
        tag.putInt("revision", observedRevision);
        return tag;
    }

    /** Loads a reference, or null when the stored dimension is malformed; the caller quarantines it. */
    @Nullable
    public static TownsteadBuildingRef load(CompoundTag tag) {
        ResourceLocation dimension = tag == null ? null : ResourceLocation.tryParse(tag.getString("dim"));
        if (dimension == null) {
            return null;
        }
        return new TownsteadBuildingRef(dimension,
                tag.contains("village") ? tag.getInt("village") : NO_VILLAGE,
                tag.contains("building") ? tag.getInt("building") : NO_VILLAGE,
                tag.contains("revision") ? tag.getInt("revision") : UNKNOWN_REVISION);
    }

    public String describe() {
        if (!bound()) {
            return dimension + " (no recognised building)";
        }
        return dimension + " village " + villageId + " building " + buildingId
                + (comparable() ? " @rev " + observedRevision : " @rev unknown");
    }
}
