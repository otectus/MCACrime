package dev.otectus.mcacrime.state.world;

import dev.otectus.mcacrime.captivity.CustodyRecord;
import dev.otectus.mcacrime.jail.JailAnchor;
import dev.otectus.mcacrime.ledger.CrimeRecord;
import dev.otectus.mcacrime.ransom.RansomState;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.ArrayList;
import java.util.Collection;
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
 * <p>Live structures: per-village reputation (§2.5), the crime ledger (§2.2), the jail roster (§7.4), and
 * the custody table (§2.3, Phase 4) keyed by captive UUID. The bounty board (§2.4) remains a reserved
 * pass-through NBT slot, preserved verbatim so a newer jar's data is never dropped by an older one — and
 * the {@code custody} slot keeps the same forward-compat guarantee: an unrecognised shape is preserved
 * untouched rather than parsed (see {@link #load}).
 */
public final class CrimeWorldData extends SavedData {

    public static final String DATA_NAME = "mcacrime";

    /** NBT keys reserved for later-phase structures; preserved verbatim across save/load. */
    private static final String[] RESERVED_KEYS = {"bounties"};

    /** villageId -> (playerUuid -> reputation delta). LinkedHashMap for stable save ordering. */
    private final Map<Integer, Map<UUID, Integer>> villageReputation = new LinkedHashMap<>();
    /** The crime ledger (§2.2): one entry per serious crime, persistent until resolved. */
    private final List<CrimeRecord> ledger = new ArrayList<>();
    /** Assigned jail anchors (§7.4 command-based assignment). */
    private final List<JailAnchor> jailAnchors = new ArrayList<>();
    /** The custody table (§2.3): captive UUID -> record. LinkedHashMap for stable save ordering. */
    private final Map<UUID, CustodyRecord> custody = new LinkedHashMap<>();
    /** Active ransom demands (§8.5), keyed by victim UUID (one open demand per victim). */
    private final Map<UUID, RansomState> ransoms = new LinkedHashMap<>();
    /** Ransom anti-farm stamps: key ("victim:uuid" / "payer:uuid" / "village:id") -> last demand game-time. */
    private final Map<String, Long> ransomCooldowns = new LinkedHashMap<>();
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

    // --- jail anchors (§7.4) ---

    public void addJailAnchor(JailAnchor anchor) {
        jailAnchors.add(anchor);
        setDirty();
    }

    public List<JailAnchor> jailAnchors() {
        return new ArrayList<>(jailAnchors);
    }

    // --- custody table (§2.3) ---

    /** Inserts/replaces the record for its captive. Idempotent by captive UUID (replay-safe). */
    public void putCustody(CustodyRecord record) {
        if (record == null || record.getCaptive() == null) {
            return;
        }
        custody.put(record.getCaptive(), record);
        setDirty();
    }

    /** The active custody for {@code captive}, or null if not held. */
    public CustodyRecord getCustody(UUID captive) {
        return custody.get(captive);
    }

    public boolean isCaptive(UUID captive) {
        return custody.containsKey(captive);
    }

    public void removeCustody(UUID captive) {
        if (custody.remove(captive) != null) {
            setDirty();
        }
    }

    public Collection<CustodyRecord> custodyRecords() {
        return new ArrayList<>(custody.values());
    }

    // --- ransom demands + cooldowns (§8.5) ---

    /** Inserts/replaces the demand for its victim. Idempotent by victim UUID (one open demand per victim). */
    public void putRansom(RansomState state) {
        if (state == null || state.getVictim() == null) {
            return;
        }
        ransoms.put(state.getVictim(), state);
        setDirty();
    }

    public RansomState getRansomForVictim(UUID victim) {
        return ransoms.get(victim);
    }

    public void removeRansom(UUID victim) {
        if (ransoms.remove(victim) != null) {
            setDirty();
        }
    }

    public Collection<RansomState> ransoms() {
        return new ArrayList<>(ransoms.values());
    }

    public long ransomCooldown(String key) {
        return ransomCooldowns.getOrDefault(key, 0L);
    }

    public void stampRansomCooldown(String key, long gameTime) {
        ransomCooldowns.put(key, gameTime);
        setDirty();
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

        ListTag anchorList = new ListTag();
        for (JailAnchor anchor : jailAnchors) {
            anchorList.add(anchor.save());
        }
        tag.put("jailRoster", anchorList);

        // Custody table (§2.3): a compound of captiveUuid -> record. Skipped if a newer jar's unrecognised
        // custody shape was stashed into `reserved` (it is re-emitted untouched below instead).
        if (!reserved.contains("custody")) {
            CompoundTag custodyTag = new CompoundTag();
            custody.forEach((uuid, record) -> custodyTag.put(uuid.toString(), record.save()));
            tag.put("custody", custodyTag);
        }

        // Ransom demands (§8.5): victimUuid -> demand; plus the anti-farm cooldown stamps.
        CompoundTag ransomTag = new CompoundTag();
        ransoms.forEach((victim, state) -> ransomTag.put(victim.toString(), state.save()));
        tag.put("ransoms", ransomTag);
        CompoundTag cooldownTag = new CompoundTag();
        ransomCooldowns.forEach(cooldownTag::putLong);
        tag.put("ransomCooldowns", cooldownTag);

        // Re-emit reserved later-phase slots untouched (bounties, plus any stashed-for-forward-compat tag).
        for (String key : reserved.getAllKeys()) {
            tag.put(key, reserved.get(key).copy());
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

        ListTag anchorList = tag.getList("jailRoster", Tag.TAG_COMPOUND);
        for (int i = 0; i < anchorList.size(); i++) {
            JailAnchor anchor = JailAnchor.load(anchorList.getCompound(i));
            if (anchor != null) {
                data.jailAnchors.add(anchor);
            }
        }

        // Custody table. Forward-compat: parse the expected compound-of-records shape (skipping malformed
        // entries); an unrecognised shape (a newer jar) is preserved verbatim in `reserved` and re-emitted.
        if (tag.contains("custody", Tag.TAG_COMPOUND)) {
            CompoundTag custodyTag = tag.getCompound("custody");
            for (String key : custodyTag.getAllKeys()) {
                try {
                    data.custody.put(UUID.fromString(key), CustodyRecord.load(custodyTag.getCompound(key)));
                } catch (RuntimeException e) {
                    // skip a malformed custody entry rather than dropping the whole store
                }
            }
        } else if (tag.contains("custody")) {
            data.reserved.put("custody", tag.get("custody").copy());
        }

        CompoundTag ransomTag = tag.getCompound("ransoms");
        for (String key : ransomTag.getAllKeys()) {
            try {
                data.ransoms.put(UUID.fromString(key), RansomState.load(ransomTag.getCompound(key)));
            } catch (RuntimeException e) {
                // skip a malformed ransom entry
            }
        }
        CompoundTag cooldownTag = tag.getCompound("ransomCooldowns");
        for (String key : cooldownTag.getAllKeys()) {
            data.ransomCooldowns.put(key, cooldownTag.getLong(key));
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
