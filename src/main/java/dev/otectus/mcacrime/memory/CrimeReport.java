package dev.otectus.mcacrime.memory;

import dev.otectus.mcacrime.api.model.CrimeCommunityKey;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;

import org.jetbrains.annotations.Nullable;
import java.util.UUID;

/**
 * An observation that reached somebody who can act on it (spec §12.3). A report is what gives a guard
 * a legal basis, and it is what finally makes per-village reputation mean something: a report is
 * scoped to one jurisdiction, and a village learns about a crime only by receiving one. That is the
 * whole defence against omniscient guards half a world away reacting to something nobody told them.
 *
 * <p>A null {@link #jurisdiction()} is the wilderness case — a crime committed away from any village.
 * It is a real state and not a missing value: the report exists, it simply has no authority to
 * propagate to, so no guard anywhere holds a warrant from it.
 */
public record CrimeReport(UUID reportId,
                          UUID incidentId,
                          UUID observationId,
                          UUID reporterId,
                          @Nullable UUID suspectId,
                          ResourceLocation actionId,
                          @Nullable CrimeCommunityKey jurisdiction,
                          long filedAt,
                          long expiresAt,
                          float confidence,
                          boolean authoritative) {

    public CrimeReport {
        confidence = suspectId != null && Float.isFinite(confidence) ? Math.max(0.0F, Math.min(1.0F, confidence)) : 0;
        expiresAt = Math.max(0L, expiresAt);
    }

    /** True in the wilderness case: filed, but with no village authority to receive it. */
    public boolean wilderness() {
        return jurisdiction == null;
    }

    public boolean expired(long now) {
        return expiresAt > 0L && now >= expiresAt;
    }

    /**
     * Whether this report justifies an arrest rather than an investigation (spec §12.3 step 4). A
     * hearing-only witness produces a low-confidence report on purpose: the guard should go and look,
     * not walk up and restrain somebody on the strength of a noise.
     */
    public boolean supportsArrest(double threshold) {
        return suspectId != null && confidence >= Math.max(0.5, threshold);
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("id", reportId);
        tag.putUUID("incident", incidentId);
        tag.putUUID("observation", observationId);
        tag.putUUID("reporter", reporterId);
        if (suspectId != null) tag.putUUID("suspect", suspectId);
        tag.putString("action", actionId.toString());
        if (jurisdiction != null) {
            tag.put("jurisdiction", jurisdiction.save());
        }
        tag.putLong("filedAt", filedAt);
        tag.putLong("expires", expiresAt);
        tag.putFloat("confidence", confidence);
        tag.putBoolean("authoritative", authoritative);
        return tag;
    }

    public static CrimeReport load(CompoundTag tag) {
        if (!tag.hasUUID("id") || !tag.hasUUID("incident") || !tag.hasUUID("reporter")) {
            throw new IllegalArgumentException("report is missing an identity");
        }
        ResourceLocation action = ResourceLocation.tryParse(tag.getString("action"));
        if (action == null) {
            throw new IllegalArgumentException("report has an unparseable action id");
        }
        CrimeCommunityKey jurisdiction = tag.contains("jurisdiction", Tag.TAG_COMPOUND)
                ? CrimeCommunityKey.load(tag.getCompound("jurisdiction")).orElse(null)
                : null;
        return new CrimeReport(
                tag.getUUID("id"),
                tag.getUUID("incident"),
                tag.hasUUID("observation") ? tag.getUUID("observation") : tag.getUUID("id"),
                tag.getUUID("reporter"),
                tag.hasUUID("suspect") ? tag.getUUID("suspect") : null,
                action,
                jurisdiction,
                tag.getLong("filedAt"),
                tag.getLong("expires"),
                tag.getFloat("confidence"),
                tag.getBoolean("authoritative"));
    }
}
