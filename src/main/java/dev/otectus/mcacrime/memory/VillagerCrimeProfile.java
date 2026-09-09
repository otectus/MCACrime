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
    private final Map<String, VictimCrimeMemory> crimeMemories = new LinkedHashMap<>();

    public java.util.List<VictimCrimeMemory> crimeMemories() { return java.util.List.copyOf(crimeMemories.values()); }
    public void remember(VictimCrimeMemory memory, int limit, long now, double decay) {
        crimeMemories.entrySet().removeIf(e -> e.getValue().importance(now, decay) < 0.28
                && now - e.getValue().timestamp() > e.getValue().duration());
        crimeMemories.merge(memory.key(), memory, (old, next) -> old.merge(next, now, decay));
        while (crimeMemories.size() > Math.max(1, Math.min(64, limit))) {
            String weakest = crimeMemories.values().stream()
                    .min(java.util.Comparator.comparingDouble(m -> m.importance(now, decay))).orElseThrow().key();
            crimeMemories.remove(weakest);
        }
    }
    public void replaceMemory(VictimCrimeMemory memory) {
        if (crimeMemories.containsKey(memory.key())) crimeMemories.put(memory.key(), memory);
    }
    public void clearMemories(UUID offender) { crimeMemories.values().removeIf(m -> m.perpetrator().equals(offender)); }

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
        net.minecraft.nbt.ListTag crimeTag = new net.minecraft.nbt.ListTag();
        crimeMemories.values().forEach(memory -> crimeTag.add(memory.save()));
        tag.put("crimeMemories", crimeTag);
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
            if (profile.offenders.size() >= MAX_OFFENDERS) break;
            try {
                profile.offenders.put(UUID.fromString(key), OffenderMemory.load(memories.getCompound(key)));
            } catch (IllegalArgumentException ignored) {
                // Skip one corrupt identity without discarding the purse or other memories.
            }
        }
        for (Tag memory : tag.getList("crimeMemories", Tag.TAG_COMPOUND)) {
            try {
                VictimCrimeMemory parsed = VictimCrimeMemory.load((CompoundTag) memory);
                profile.remember(parsed, 64, parsed.updatedAt(), 1);
            } catch (IllegalArgumentException ignored) {
                // One malformed memory must not discard the purse or valid identities.
            }
        }
        return profile;
    }
}
