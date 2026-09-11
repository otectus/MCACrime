package dev.otectus.mcacrime.state.world;

import net.minecraft.nbt.CompoundTag;

import java.util.UUID;

/**
 * One villager's stint as somebody's accomplice, as persisted (0.7.0).
 *
 * <p>It exists so that helping is accountable rather than free. The effect itself is memory-only — a
 * lookout who was watching the street when the server stopped is not watching it now — but who agreed
 * to help, who they helped, and whether anybody saw them do it has to outlive the session, because
 * being wanted is the consequence and a consequence that a restart erases is not one.
 *
 * <p>{@code priorArrests} lives here rather than on {@code CriminalVillagerRecord} because it counts
 * lawful arrests <em>of this relative</em>, and a relative is not a thief: a villager can be an
 * accomplice without ever holding a criminal job, so the job record would be absent for exactly the
 * villagers bail needs to price. It is the compounding term in {@code BailQuote}, so a family that
 * keeps buying the same cousin out pays more each time.
 */
public record AccompliceRecord(UUID villager, UUID player, String role, String incidentId,
                               long startedAtTick, long expiresAtTick, boolean wanted,
                               int timesAssisted, long lastAssistedTick, int priorArrests) {

    public AccompliceRecord {
        role = role == null ? "" : role;
        incidentId = incidentId == null ? "" : incidentId;
        timesAssisted = Math.max(0, timesAssisted);
        priorArrests = Math.max(0, priorArrests);
    }

    /** Whether the agreement is still running at {@code now}. A wanted record never expires quietly. */
    public boolean active(long now) {
        return expiresAtTick > now;
    }

    /** The same record, marked wanted. Called when somebody saw the help, or the principal was taken. */
    public AccompliceRecord asWanted() {
        return wanted ? this : new AccompliceRecord(villager, player, role, incidentId, startedAtTick,
                expiresAtTick, true, timesAssisted, lastAssistedTick, priorArrests);
    }

    /** The same record with one more arrest counted and the warrant spent. */
    public AccompliceRecord arrested() {
        return new AccompliceRecord(villager, player, role, incidentId, startedAtTick, expiresAtTick,
                false, timesAssisted, lastAssistedTick, priorArrests + 1);
    }

    /** The same record renewed for another job of {@code role}, ending at {@code expiresAt}. */
    public AccompliceRecord renewed(String role, String incidentId, long now, long expiresAt) {
        return new AccompliceRecord(villager, player, role, incidentId, now, expiresAt, wanted,
                timesAssisted + 1, now, priorArrests);
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("villager", villager);
        tag.putUUID("player", player);
        tag.putString("role", role);
        if (!incidentId.isEmpty()) {
            tag.putString("incidentId", incidentId);
        }
        tag.putLong("startedAtTick", startedAtTick);
        tag.putLong("expiresAtTick", expiresAtTick);
        tag.putBoolean("wanted", wanted);
        tag.putInt("timesAssisted", timesAssisted);
        tag.putLong("lastAssistedTick", lastAssistedTick);
        tag.putInt("priorArrests", priorArrests);
        return tag;
    }

    /** Throws on a tag with no villager or player id; the caller skips that one entry. */
    public static AccompliceRecord load(CompoundTag tag) {
        return new AccompliceRecord(
                tag.getUUID("villager"),
                tag.getUUID("player"),
                tag.getString("role"),
                tag.contains("incidentId") ? tag.getString("incidentId") : "",
                tag.getLong("startedAtTick"),
                tag.getLong("expiresAtTick"),
                tag.getBoolean("wanted"),
                tag.getInt("timesAssisted"),
                tag.getLong("lastAssistedTick"),
                tag.getInt("priorArrests"));
    }
}
