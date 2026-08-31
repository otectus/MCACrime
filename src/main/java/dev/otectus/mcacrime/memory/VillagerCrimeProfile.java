package dev.otectus.mcacrime.memory;

import dev.otectus.mcacrime.economy.account.VillagerPurse;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Persisted purse and bounded offender memory for one villager. */
public final class VillagerCrimeProfile {
    private static final int MAX_OFFENDERS = 16;

    private final UUID villager;
    private final VillagerPurse purse;
    private final Map<UUID, OffenderMemory> offenders = new LinkedHashMap<>();
    private long recoveryUntil;

    public VillagerCrimeProfile(UUID villager, VillagerPurse purse) {
        this.villager = villager;
        this.purse = purse;
    }

    public UUID villager() { return villager; }
    public VillagerPurse purse() { return purse; }
    public long recoveryUntil() { return recoveryUntil; }
    public void setRecoveryUntil(long value) { recoveryUntil = Math.max(recoveryUntil, value); }

    public OffenderMemory memory(UUID offender) {
        OffenderMemory existing = offenders.remove(offender);
        if (existing == null) {
            existing = new OffenderMemory();
        }
        offenders.put(offender, existing);
        while (offenders.size() > MAX_OFFENDERS) {
            offenders.remove(offenders.keySet().iterator().next());
        }
        return existing;
    }

    public Map<UUID, OffenderMemory> offenderMemories() {
        return Map.copyOf(offenders);
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("villager", villager);
        tag.put("purse", purse.save());
        tag.putLong("recoveryUntil", recoveryUntil);
        CompoundTag memoryTag = new CompoundTag();
        offenders.forEach((uuid, memory) -> memoryTag.put(uuid.toString(), memory.save()));
        tag.put("offenders", memoryTag);
        return tag;
    }

    public static VillagerCrimeProfile load(CompoundTag tag) {
        if (!tag.hasUUID("villager") || !tag.contains("purse", Tag.TAG_COMPOUND)) {
            return null;
        }
        VillagerCrimeProfile profile = new VillagerCrimeProfile(tag.getUUID("villager"),
                VillagerPurse.load(tag.getCompound("purse")));
        profile.recoveryUntil = Math.max(0L, tag.getLong("recoveryUntil"));
        CompoundTag memories = tag.getCompound("offenders");
        for (String key : memories.getAllKeys()) {
            try {
                profile.offenders.put(UUID.fromString(key), OffenderMemory.load(memories.getCompound(key)));
            } catch (IllegalArgumentException ignored) {
                // Skip one corrupt identity without discarding the purse or other memories.
            }
        }
        return profile;
    }
}
