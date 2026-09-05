package dev.otectus.mcacrime.state.world;

import dev.otectus.mcacrime.job.CriminalJob;
import net.minecraft.nbt.CompoundTag;

import org.jetbrains.annotations.Nullable;
import java.util.UUID;

/**
 * One villager's criminal occupation, as persisted (0.5.1).
 *
 * <p>This is authoritative, not MCA's profession. {@code previousProfessionId} is kept so that
 * turning the "present this job as an MCA profession" key back off can restore what the villager was
 * doing before, rather than leaving them permanently a fence because a setting changed once. It is
 * nullable because a job assigned while presentation was off never took one away.
 */
public record CriminalVillagerRecord(UUID villager, CriminalJob job, long assignedDay, long lastMugAt,
                                     long lastSeenDay, boolean wildOrigin, long personalitySeed,
                                     @Nullable String previousProfessionId) {

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
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
        return tag;
    }

    /** Throws on a tag with no villager id; the caller skips that one entry. */
    public static CriminalVillagerRecord load(CompoundTag tag) {
        return new CriminalVillagerRecord(
                tag.getUUID("villager"),
                CriminalJob.parse(tag.getString("job")),
                tag.getLong("assignedDay"),
                tag.getLong("lastMugAt"),
                tag.getLong("lastSeenDay"),
                tag.getBoolean("wildOrigin"),
                tag.getLong("personalitySeed"),
                tag.contains("previousProfessionId") ? tag.getString("previousProfessionId") : null);
    }
}
