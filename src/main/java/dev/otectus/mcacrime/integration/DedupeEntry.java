package dev.otectus.mcacrime.integration;

import dev.otectus.mcacrime.api.result.CrimeMutationStatus;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The remembered outcome of one already-applied mutation, so replaying it changes nothing.
 *
 * <p>A quest reward fires twice because the player relogged mid-turn-in; a dialogue click arrives
 * twice because the packet was retried; the outbox re-sends after a crash. In each case the second
 * attempt must return the <em>first</em> answer rather than applying the change again — otherwise a
 * player pays one fine and clears two cases, or loses karma twice for one deed.
 *
 * <h2>What this is not for</h2>
 *
 * <p>This cache expires. It is sized for replay windows, not for history. Long-lived cross-mod
 * identity — the link between a crime case and the civic incident it produced — lives on the record
 * itself, where it cannot age out. If an expiring cache were the only thing remembering that link,
 * a quiet server would eventually forget it and record the deed a second time.
 */
public record DedupeEntry(String key, CrimeMutationStatus status, List<UUID> affectedIds,
                          long createdGameTime, long expiresGameTime) {

    /** Longest key stored. Callers are expected to keep well under this. */
    public static final int MAX_KEY_LENGTH = 128;
    /** How many affected ids one entry remembers. */
    public static final int MAX_AFFECTED_IDS = 16;

    public DedupeEntry {
        key = key == null ? "" : truncate(key);
        status = status == null ? CrimeMutationStatus.APPLIED : status;
        affectedIds = affectedIds == null || affectedIds.isEmpty()
                ? List.of()
                : List.copyOf(affectedIds.subList(0, Math.min(affectedIds.size(), MAX_AFFECTED_IDS)));
    }

    private static String truncate(String raw) {
        return raw.length() <= MAX_KEY_LENGTH ? raw : raw.substring(0, MAX_KEY_LENGTH);
    }

    /** Whether this entry has aged out and may be pruned. */
    public boolean expired(long gameTime) {
        return gameTime >= expiresGameTime;
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putString("key", key);
        tag.putString("status", status.name());
        if (!affectedIds.isEmpty()) {
            ListTag ids = new ListTag();
            affectedIds.forEach(id -> ids.add(NbtUtils.createUUID(id)));
            tag.put("affected", ids);
        }
        tag.putLong("created", createdGameTime);
        tag.putLong("expires", expiresGameTime);
        return tag;
    }

    public static DedupeEntry load(CompoundTag tag) {
        List<UUID> affected = new ArrayList<>();
        if (tag.contains("affected", Tag.TAG_LIST)) {
            ListTag ids = tag.getList("affected", Tag.TAG_INT_ARRAY);
            for (int i = 0; i < ids.size(); i++) {
                try {
                    affected.add(NbtUtils.loadUUID(ids.get(i)));
                } catch (RuntimeException e) {
                    // Skip one malformed id rather than dropping the entry.
                }
            }
        }
        return new DedupeEntry(tag.getString("key"),
                CrimeMutationStatus.parse(tag.getString("status")),
                affected,
                tag.getLong("created"),
                tag.getLong("expires"));
    }
}
