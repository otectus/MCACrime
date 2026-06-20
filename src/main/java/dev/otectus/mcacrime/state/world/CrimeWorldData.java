package dev.otectus.mcacrime.state.world;

import dev.otectus.mcacrime.ledger.CrimeRecord;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The single world-level store for cross-player / world-scoped crime data (spec §2.2–§2.5). Pinned to
 * the overworld's {@code DimensionDataStorage} so one store holds everything regardless of where
 * players currently are, and persists to {@code <world>/data/mcacrime.dat} on autosave/shutdown — the
 * same mechanism MCA's own village/family data uses, so its lifetime matches data the player already
 * trusts. {@link #setDirty()} is called after every mutation.
 *
 * <p>0.1.0 holds one live structure: per-village reputation (§2.5), an <em>optional local modifier</em>
 * on top of the authoritative global Karma. Nothing writes to it yet (crime/rescue hooks are later
 * phases) — it exists, persists, and round-trips so the file is real and the structure is proven. The
 * crime ledger, bounty board, custody table, and jail roster (§2.2–§2.4) are reserved as pass-through
 * NBT slots: this version preserves them verbatim so a newer jar's data is never dropped by an older one.
 */
public final class CrimeWorldData extends SavedData {

    public static final String DATA_NAME = "mcacrime";

    /** NBT keys reserved for later-phase structures; preserved verbatim across save/load. */
    private static final String[] RESERVED_KEYS = {"bounties", "custody", "jailRoster"};

    /** villageId -> (playerUuid -> reputation delta). LinkedHashMap for stable save ordering. */
    private final Map<Integer, Map<UUID, Integer>> villageReputation = new LinkedHashMap<>();
    /** The crime ledger (§2.2): one entry per serious crime, persistent until resolved. */
    private final List<CrimeRecord> ledger = new ArrayList<>();
    /** Verbatim copy of any reserved later-phase tags found on disk, re-emitted untouched on save. */
    private final CompoundTag reserved = new CompoundTag();

    public CrimeWorldData() {
    }

    public static CrimeWorldData get(MinecraftServer server) {
        ServerLevel overworld = server.overworld();
        return overworld.getDataStorage().computeIfAbsent(CrimeWorldData::load, CrimeWorldData::new, DATA_NAME);
    }

    // --- per-village reputation (§2.5) ---

    public int reputation(int villageId, UUID player) {
        Map<UUID, Integer> village = villageReputation.get(villageId);
        return village == null ? 0 : village.getOrDefault(player, 0);
    }

    public void addReputation(int villageId, UUID player, int delta) {
        if (delta == 0) {
            return;
        }
        villageReputation.computeIfAbsent(villageId, k -> new LinkedHashMap<>())
                .merge(player, delta, Integer::sum);
        setDirty();
    }

    // --- crime ledger (§2.2) ---

    /** Appends a record. Idempotent: a record whose id is already present is ignored (replay-safe). */
    public void addRecord(CrimeRecord record) {
        if (containsRecord(record.id())) {
            return;
        }
        ledger.add(record);
        setDirty();
    }

    public boolean containsRecord(UUID id) {
        for (CrimeRecord r : ledger) {
            if (r.id().equals(id)) {
                return true;
            }
        }
        return false;
    }

    /** Records committed by {@code offender}, newest first. */
    public List<CrimeRecord> recordsForOffender(UUID offender) {
        List<CrimeRecord> out = new ArrayList<>();
        for (int i = ledger.size() - 1; i >= 0; i--) {
            CrimeRecord r = ledger.get(i);
            if (r.offender().equals(offender)) {
                out.add(r);
            }
        }
        return out;
    }

    public int ledgerSize() {
        return ledger.size();
    }

    // --- persistence ---

    @Override
    public CompoundTag save(CompoundTag tag) {
        CompoundTag villages = new CompoundTag();
        villageReputation.forEach((villageId, players) -> {
            CompoundTag perPlayer = new CompoundTag();
            players.forEach((uuid, delta) -> perPlayer.putInt(uuid.toString(), delta));
            villages.put(Integer.toString(villageId), perPlayer);
        });
        tag.put("villageReputation", villages);

        ListTag ledgerList = new ListTag();
        for (CrimeRecord record : ledger) {
            ledgerList.add(record.save());
        }
        tag.put("ledger", ledgerList);

        // Re-emit reserved later-phase slots untouched.
        for (String key : RESERVED_KEYS) {
            if (reserved.contains(key)) {
                tag.put(key, reserved.get(key).copy());
            }
        }
        return tag;
    }

    public static CrimeWorldData load(CompoundTag tag) {
        CrimeWorldData data = new CrimeWorldData();
        CompoundTag villages = tag.getCompound("villageReputation");
        for (String villageKey : villages.getAllKeys()) {
            int villageId;
            try {
                villageId = Integer.parseInt(villageKey);
            } catch (NumberFormatException e) {
                continue; // skip a malformed village id
            }
            CompoundTag perPlayer = villages.getCompound(villageKey);
            Map<UUID, Integer> players = new LinkedHashMap<>();
            for (String uuidKey : perPlayer.getAllKeys()) {
                try {
                    players.put(UUID.fromString(uuidKey), perPlayer.getInt(uuidKey));
                } catch (IllegalArgumentException e) {
                    // skip a malformed UUID key
                }
            }
            if (!players.isEmpty()) {
                data.villageReputation.put(villageId, players);
            }
        }

        ListTag ledgerList = tag.getList("ledger", Tag.TAG_COMPOUND);
        for (int i = 0; i < ledgerList.size(); i++) {
            try {
                data.ledger.add(CrimeRecord.load(ledgerList.getCompound(i)));
            } catch (RuntimeException e) {
                // skip a malformed ledger entry rather than dropping the whole store
            }
        }

        // Capture reserved later-phase slots verbatim for forward compatibility.
        for (String key : RESERVED_KEYS) {
            if (tag.contains(key)) {
                data.reserved.put(key, tag.get(key).copy());
            }
        }
        return data;
    }
}
