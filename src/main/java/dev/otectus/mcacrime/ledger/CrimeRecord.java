package dev.otectus.mcacrime.ledger;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;

import javax.annotation.Nullable;
import java.util.OptionalInt;
import java.util.UUID;

/**
 * One ledger entry for a serious crime (spec §2.2) — the backbone for bounties, repeat-offender scaling,
 * NPC memory, and admin debugging. Persisted in {@code mcacrime.dat} via {@link CrimeWorldData} so a
 * record outlives Karma recovery until explicitly resolved. NBT save/load follows the project house style
 * (absent-key tolerant).
 *
 * <p>{@code fineAmount}/{@code jailTicks} are 0 and {@code resolution} is {@link Resolution#UNRESOLVED}
 * in this phase; the punishment phases populate them.
 */
public record CrimeRecord(UUID id, UUID offender, @Nullable UUID victim, ResourceLocation type,
                          OptionalInt villageId, boolean witnessed, long timeCommitted,
                          long heatGenerated, long karmaDelta, long fineAmount, long jailTicks,
                          Resolution resolution) {

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("id", id);
        tag.putUUID("offender", offender);
        if (victim != null) {
            tag.putUUID("victim", victim);
        }
        tag.putString("type", type.toString());
        villageId.ifPresent(v -> tag.putInt("villageId", v));
        tag.putBoolean("witnessed", witnessed);
        tag.putLong("timeCommitted", timeCommitted);
        tag.putLong("heatGenerated", heatGenerated);
        tag.putLong("karmaDelta", karmaDelta);
        tag.putLong("fineAmount", fineAmount);
        tag.putLong("jailTicks", jailTicks);
        tag.putString("resolution", resolution.name());
        return tag;
    }

    public static CrimeRecord load(CompoundTag tag) {
        UUID victim = tag.hasUUID("victim") ? tag.getUUID("victim") : null;
        OptionalInt villageId = tag.contains("villageId") ? OptionalInt.of(tag.getInt("villageId")) : OptionalInt.empty();
        ResourceLocation type = ResourceLocation.tryParse(tag.getString("type"));
        return new CrimeRecord(
                tag.getUUID("id"),
                tag.getUUID("offender"),
                victim,
                type,
                villageId,
                tag.getBoolean("witnessed"),
                tag.getLong("timeCommitted"),
                tag.getLong("heatGenerated"),
                tag.getLong("karmaDelta"),
                tag.getLong("fineAmount"),
                tag.getLong("jailTicks"),
                Resolution.parse(tag.getString("resolution")));
    }
}
