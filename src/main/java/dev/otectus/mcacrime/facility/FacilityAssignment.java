package dev.otectus.mcacrime.facility;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;

import javax.annotation.Nullable;
import java.util.UUID;

/**
 * One building an operator has given MCA: Crime a job: this role, at this anchor, for this many
 * prisoners.
 *
 * <p>The anchor is stored separately from the building reference rather than derived from it, and the
 * difference matters twice. A building's centre is not necessarily somewhere a prisoner can stand, so
 * the anchor is the position an operator chose; and when Townstead is absent or a village has been
 * restructured, the anchor is the only part still meaningful, which is what lets a facility degrade to
 * "unverified but usable" instead of vanishing.
 *
 * @param id         this assignment's own id, stable across restarts and used by {@code /crime facility}
 * @param ref        the building this was assigned to, with the revision it was read at
 * @param role       what MCA: Crime uses it for
 * @param anchor     the position an arrest is routed to and a prisoner released at
 * @param capacity   how many prisoners may be reserved into it at once; zero for a role that holds none
 * @param assignedBy the name of whoever ran the command, for the operator-visible record
 * @param gameTime   when it was assigned
 */
public record FacilityAssignment(UUID id, TownsteadBuildingRef ref, FacilityRole role, BlockPos anchor,
                                 int capacity, String assignedBy, long gameTime) {

    public FacilityAssignment {
        if (id == null) {
            throw new IllegalArgumentException("a facility assignment must have an id");
        }
        if (ref == null) {
            throw new IllegalArgumentException("a facility assignment must have a building reference");
        }
        if (role == null) {
            throw new IllegalArgumentException("a facility assignment must have a role");
        }
        if (anchor == null) {
            throw new IllegalArgumentException("a facility assignment must have an anchor");
        }
        capacity = Math.max(0, capacity);
        assignedBy = assignedBy == null || assignedBy.isBlank() ? "unknown" : assignedBy;
    }

    /** A new assignment with a fresh id and the role's own default capacity. */
    public static FacilityAssignment of(TownsteadBuildingRef ref, FacilityRole role, BlockPos anchor,
                                        String assignedBy, long gameTime) {
        return new FacilityAssignment(UUID.randomUUID(), ref, role, anchor, role.defaultCapacity(),
                assignedBy, gameTime);
    }

    /** Whether this assignment can take prisoners at all. */
    public boolean holdsPrisoners() {
        return role.holdsPrisoners() && capacity > 0;
    }

    /** The same assignment with a re-stamped revision, after a revalidation confirmed the building. */
    public FacilityAssignment revalidatedAt(int revision) {
        TownsteadBuildingRef updated = ref.observedAt(revision);
        return updated == ref ? this
                : new FacilityAssignment(id, updated, role, anchor, capacity, assignedBy, gameTime);
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("id", id);
        tag.put("ref", ref.save());
        tag.putString("role", role.id());
        tag.putInt("x", anchor.getX());
        tag.putInt("y", anchor.getY());
        tag.putInt("z", anchor.getZ());
        tag.putInt("capacity", capacity);
        tag.putString("by", assignedBy);
        tag.putLong("at", gameTime);
        return tag;
    }

    /**
     * Loads one assignment, or null when it is unreadable.
     *
     * <p>Null rather than a repaired record: a facility whose role or dimension cannot be read is a
     * facility nobody can validate, and guessing one would route an arrest somewhere nobody chose. The
     * caller quarantines the row so the operator can still see it.
     */
    @Nullable
    public static FacilityAssignment load(CompoundTag tag) {
        if (tag == null || !tag.hasUUID("id")) {
            return null;
        }
        TownsteadBuildingRef ref = TownsteadBuildingRef.load(tag.getCompound("ref"));
        FacilityRole role = FacilityRole.parse(tag.getString("role")).orElse(null);
        if (ref == null || role == null) {
            return null;
        }
        return new FacilityAssignment(tag.getUUID("id"), ref, role,
                new BlockPos(tag.getInt("x"), tag.getInt("y"), tag.getInt("z")),
                tag.getInt("capacity"), tag.getString("by"), tag.getLong("at"));
    }

    /** One line for {@code /crime facility list}. */
    public String describe() {
        return role.label() + " " + shortId() + " at " + anchor.getX() + "," + anchor.getY() + ","
                + anchor.getZ() + " (capacity " + capacity + ", " + ref.describe()
                + ", assigned by " + assignedBy + ")";
    }

    /** The first eight characters of the id, which is what an operator types back at a command. */
    public String shortId() {
        return id.toString().substring(0, 8);
    }
}
