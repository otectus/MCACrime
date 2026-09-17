package dev.otectus.mcacrime.compat;

/**
 * The Townstead/MCA building registered at a position: what it is, who owns it, and where it ends.
 *
 * <p>Bounds are inclusive and already normalised to min/max by Townstead's own facade, so
 * {@link #contains} is a plain comparison. {@code villageId} is the same MCA village id MCA: Crime
 * already keys communities by, which is what lets a building answer "whose jurisdiction is this?"
 * without a second lookup.
 *
 * <p>Two lookups produce this record and they do not know the same things.
 * {@link TownsteadBridge#buildingAt} is Townstead's own facade: one building, the first one containing
 * the block in the nearest village, with a {@code size} and no revision.
 * {@link TownsteadBridge#buildingsAt} is the enumeration behind
 * {@link TownsteadCapability#BUILDING_ENUMERATION}: every overlapping building in every village of the
 * level, each carrying the {@link #kind} Townstead filed it under and the {@link #revision} of the
 * village record it came from, which is what lets a stored reference be revalidated later instead of
 * polled. An enumerated building has no {@code size} of its own and reports zero.
 *
 * <p>{@link #revision} is {@link #UNKNOWN_REVISION} for the facade lookup, and that is a fact rather
 * than a default: a building read without a revision cannot be compared against a stored one, so the
 * two must not look the same to a caller.
 */
public record TownsteadBuildingView(
        int id,
        int villageId,
        String type,
        int size,
        int centerX,
        int centerY,
        int centerZ,
        int minX,
        int minY,
        int minZ,
        int maxX,
        int maxY,
        int maxZ,
        String kind,
        int revision) {

    /** The revision of a building read through a lookup that does not carry one. */
    public static final int UNKNOWN_REVISION = -1;

    public TownsteadBuildingView {
        type = type == null ? "" : type;
        kind = kind == null ? "" : kind;
        revision = revision < 0 ? UNKNOWN_REVISION : revision;
    }

    /** Whether this reading carries a village revision a stored reference can be compared against. */
    public boolean revisionKnown() {
        return revision != UNKNOWN_REVISION;
    }

    /** Whether a block position falls inside this building's bounds, inclusive on every axis. */
    public boolean contains(int x, int y, int z) {
        return x >= minX && x <= maxX && y >= minY && y <= maxY && z >= minZ && z <= maxZ;
    }

    /** Horizontal-only containment, for a cell site probe that does not care about roof height. */
    public boolean containsColumn(int x, int z) {
        return x >= minX && x <= maxX && z >= minZ && z <= maxZ;
    }

    /** Whether any part of the inclusive box {@code min..max} overlaps this building. */
    public boolean intersects(int fromX, int fromY, int fromZ, int toX, int toY, int toZ) {
        return Math.min(fromX, toX) <= maxX && Math.max(fromX, toX) >= minX
                && Math.min(fromY, toY) <= maxY && Math.max(fromY, toY) >= minY
                && Math.min(fromZ, toZ) <= maxZ && Math.max(fromZ, toZ) >= minZ;
    }

    public String describe() {
        return "building: " + (type.isEmpty() ? "?" : type) + " #" + id + " in village " + villageId
                + (kind.isEmpty() ? "" : ", kind " + kind)
                + ", size " + size + ", centre " + centerX + "," + centerY + "," + centerZ
                + ", bounds " + minX + "," + minY + "," + minZ + " to " + maxX + "," + maxY + "," + maxZ
                + (revisionKnown() ? ", revision " + revision : ", revision unknown");
    }
}
