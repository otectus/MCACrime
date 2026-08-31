package dev.otectus.mcacrime.state.world;

import dev.otectus.mcacrime.McaCrime;
import dev.otectus.mcacrime.api.model.CrimeCommunityKey;
import dev.otectus.mcacrime.captivity.CustodyRecord;
import dev.otectus.mcacrime.integration.CrimeIntegrationOperation;
import dev.otectus.mcacrime.integration.DedupeEntry;
import dev.otectus.mcacrime.jail.JailAnchor;
import dev.otectus.mcacrime.ledger.CrimeContext;
import dev.otectus.mcacrime.ledger.CrimeRecord;
import dev.otectus.mcacrime.memory.VillagerCrimeProfile;
import dev.otectus.mcacrime.ransom.RansomState;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.function.Supplier;

/**
 * The single world-level store for cross-player / world-scoped crime data (spec §2.2–§2.5). Pinned to
 * the overworld's {@code DimensionDataStorage} so one store holds everything regardless of where
 * players currently are, and persists to {@code <world>/data/mcacrime.dat} on autosave/shutdown — the
 * same mechanism MCA's own village/family data uses, so its lifetime matches data the player already
 * trusts. {@link #setDirty()} is called after every mutation.
 *
 * <h2>Three compatibility mechanisms, doing three different jobs</h2>
 *
 * <ul>
 *   <li><b>{@code schema}</b> — backward compatibility. An older file is stepped up in memory by
 *       {@link CrimeDataMigrations} before anything is parsed. A missing key reads as 0, so every
 *       pre-existing world migrates with no special case.</li>
 *   <li><b>{@code reserved}</b> — forward compatibility. A tag this build does not recognise (the
 *       {@code bounties} slot, or a {@code custody} shape written by a newer jar) is kept verbatim
 *       and re-emitted untouched, so downgrading does not destroy data.</li>
 *   <li><b>Skip-malformed</b> — one bad entry is dropped, never the whole store. A single corrupt
 *       ledger row must not cost a player their entire criminal history.</li>
 * </ul>
 *
 * <p>The ledger is a {@code LinkedHashMap} keyed by record id but still serialises as the same
 * {@code ListTag} in the same insertion order, so the on-disk form is unchanged while lookup and
 * replace stop being linear scans.
 */
public final class CrimeWorldData extends SavedData {

    public static final String DATA_NAME = "mcacrime";

    /** NBT keys reserved for later-phase structures; preserved verbatim across save/load. */
    private static final String[] RESERVED_KEYS = {"bounties"};

    /** Where a whole from-the-future store is parked rather than parsed. */
    private static final String FUTURE_KEY = "__future";

    /** Global ceiling on pending integration work, so a permanently broken bridge cannot grow forever. */
    private static final int MAX_PENDING_OPERATIONS = 2000;
    /** Ceiling on retained dead letters. */
    private static final int MAX_DEAD_LETTERS = 200;
    /** Ceiling on remembered dedupe keys per player. */
    private static final int MAX_DEDUPE_PER_PLAYER = 64;

    /** community -> (playerUuid -> standing delta). LinkedHashMap for stable save ordering. */
    private final Map<CrimeCommunityKey, Map<UUID, Integer>> villageReputation = new LinkedHashMap<>();
    /** The crime ledger (§2.2), keyed by record id; iteration order is append order. */
    private final Map<UUID, CrimeRecord> ledger = new LinkedHashMap<>();
    /** offender -> record ids, rebuilt on load. Not serialised: it is derivable from the ledger. */
    private final Map<UUID, List<UUID>> byOffender = new LinkedHashMap<>();
    /** Assigned jail anchors (§7.4 command-based assignment). */
    private final List<JailAnchor> jailAnchors = new ArrayList<>();
    /** The custody table (§2.3): captive UUID -> record. LinkedHashMap for stable save ordering. */
    private final Map<UUID, CustodyRecord> custody = new LinkedHashMap<>();
    /** Active ransom demands (§8.5), keyed by victim UUID (one open demand per victim). */
    private final Map<UUID, RansomState> ransoms = new LinkedHashMap<>();
    /** Ransom anti-farm stamps: key ("victim:uuid" / "payer:uuid" / "village:id") -> last demand game-time. */
    private final Map<String, Long> ransomCooldowns = new LinkedHashMap<>();
    /** Villager purse, recovery, and offender memory, independent of chunk loading. */
    private final Map<UUID, VillagerCrimeProfile> villagerProfiles = new LinkedHashMap<>();
    /** Day-window counters used by profitable hostile actions. */
    private final Map<String, Long> actionCounters = new LinkedHashMap<>();
    /** Finite village authority balances, keyed by dimension-aware community. */
    private final Map<String, Long> villageTreasuries = new LinkedHashMap<>();
    /** Completed economic transaction ids; bounds replay in the persisted world. */
    private final Set<UUID> transactionReceipts = new LinkedHashSet<>();
    /** Pending cross-mod writes, keyed by operation id. */
    private final Map<UUID, CrimeIntegrationOperation> outbox = new LinkedHashMap<>();
    /** Operations given up on, kept bounded for an operator to inspect. */
    private final List<CrimeIntegrationOperation> deadLetters = new ArrayList<>();
    /** player -> (dedupe key -> remembered outcome). */
    private final Map<UUID, Map<String, DedupeEntry>> dedupe = new LinkedHashMap<>();
    /** Verbatim copy of any reserved later-phase tags found on disk, re-emitted untouched on save. */
    private final CompoundTag reserved = new CompoundTag();

    /** True when the file came from a newer jar and nothing was parsed. */
    private boolean fromTheFuture;

    public CrimeWorldData() {
    }

    public static CrimeWorldData get(MinecraftServer server) {
        ServerLevel overworld = server.overworld();
        return overworld.getDataStorage().computeIfAbsent(CrimeWorldData::load, CrimeWorldData::new, DATA_NAME);
    }

    /**
     * True when this store was written by a newer version and is being carried through untouched.
     * Every mutation is refused in that state — writing a downgraded file over a newer one would
     * destroy data that this build simply does not understand.
     */
    public boolean isReadOnlyFutureData() {
        return fromTheFuture;
    }

    // --- per-community standing (§2.5) ---

    public int reputation(CrimeCommunityKey community, UUID player) {
        Map<UUID, Integer> village = villageReputation.get(community);
        return village == null ? 0 : village.getOrDefault(player, 0);
    }

    public void addReputation(CrimeCommunityKey community, UUID player, int delta) {
        if (community == null || delta == 0 || fromTheFuture) {
            return;
        }
        villageReputation.computeIfAbsent(community, k -> new LinkedHashMap<>())
                .merge(player, delta, Integer::sum);
        setDirty();
    }

    /** Overwrites rather than accumulates — used to mirror a companion mod's canonical score. */
    public void setReputation(CrimeCommunityKey community, UUID player, int value) {
        if (community == null || fromTheFuture) {
            return;
        }
        villageReputation.computeIfAbsent(community, k -> new LinkedHashMap<>()).put(player, value);
        setDirty();
    }

    /**
     * @deprecated a bare village id is ambiguous across dimensions. Resolves to the overworld so
     *         existing callers keep working; use {@link #reputation(CrimeCommunityKey, UUID)}.
     */
    @Deprecated
    public int reputation(int villageId, UUID player) {
        return CrimeCommunityKey.of(new ResourceLocation(CrimeDataMigrations.ASSUMED_DIMENSION), villageId)
                .map(key -> reputation(key, player)).orElse(0);
    }

    /**
     * @deprecated see {@link #reputation(int, UUID)}; use
     *         {@link #addReputation(CrimeCommunityKey, UUID, int)}.
     */
    @Deprecated
    public void addReputation(int villageId, UUID player, int delta) {
        CrimeCommunityKey.of(new ResourceLocation(CrimeDataMigrations.ASSUMED_DIMENSION), villageId)
                .ifPresent(key -> addReputation(key, player, delta));
    }

    // --- crime ledger (§2.2) ---

    /** Appends a record. Idempotent: a record whose id is already present is ignored (replay-safe). */
    public void addRecord(CrimeRecord record) {
        if (record == null || fromTheFuture || ledger.containsKey(record.id())) {
            return;
        }
        ledger.put(record.id(), record);
        byOffender.computeIfAbsent(record.offender(), k -> new ArrayList<>()).add(record.id());
        setDirty();
    }

    /**
     * Replaces an existing record in place, keeping its position in the ledger. Does nothing for an
     * unknown id — a lifecycle change must never silently create the case it thought it was updating.
     */
    public boolean replaceRecord(CrimeRecord record) {
        if (record == null || fromTheFuture || !ledger.containsKey(record.id())) {
            return false;
        }
        ledger.put(record.id(), record);
        setDirty();
        return true;
    }

    public boolean containsRecord(UUID id) {
        return ledger.containsKey(id);
    }

    public Optional<CrimeRecord> recordById(UUID id) {
        return Optional.ofNullable(ledger.get(id));
    }

    /** Records committed by {@code offender}, newest first. */
    public List<CrimeRecord> recordsForOffender(UUID offender) {
        List<UUID> ids = byOffender.get(offender);
        if (ids == null || ids.isEmpty()) {
            return new ArrayList<>();
        }
        List<CrimeRecord> out = new ArrayList<>(ids.size());
        for (int i = ids.size() - 1; i >= 0; i--) {
            CrimeRecord record = ledger.get(ids.get(i));
            if (record != null) {
                out.add(record);
            }
        }
        return out;
    }

    /**
     * Still-actionable records for {@code offender}, oldest first — the order a fine settles them in,
     * so the longest-standing debt is always paid before the freshest.
     */
    public List<CrimeRecord> actionableFor(UUID offender) {
        List<CrimeRecord> out = new ArrayList<>();
        for (CrimeRecord record : recordsForOffender(offender)) {
            if (record.actionable()) {
                out.add(record);
            }
        }
        out.sort(Comparator.comparingLong(CrimeRecord::timeCommitted)
                .thenComparing(record -> record.id().toString()));
        return out;
    }

    public int ledgerSize() {
        return ledger.size();
    }

    // --- jail anchors (§7.4) ---

    public void addJailAnchor(JailAnchor anchor) {
        if (fromTheFuture) {
            return;
        }
        jailAnchors.add(anchor);
        setDirty();
    }

    public List<JailAnchor> jailAnchors() {
        return new ArrayList<>(jailAnchors);
    }

    // --- custody table (§2.3) ---

    /** Inserts/replaces the record for its captive. Idempotent by captive UUID (replay-safe). */
    public void putCustody(CustodyRecord record) {
        if (record == null || record.getCaptive() == null || fromTheFuture) {
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
        if (state == null || state.getVictim() == null || fromTheFuture) {
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
        if (fromTheFuture) {
            return;
        }
        ransomCooldowns.put(key, gameTime);
        setDirty();
    }

    // --- action economy + villager memory (0.3.0) ---

    public VillagerCrimeProfile villagerProfile(UUID villager, Supplier<VillagerCrimeProfile> factory) {
        VillagerCrimeProfile profile = villagerProfiles.get(villager);
        if (profile == null && !fromTheFuture) {
            profile = factory.get();
            villagerProfiles.put(villager, profile);
            setDirty();
        }
        return profile;
    }

    public Optional<VillagerCrimeProfile> villagerProfile(UUID villager) {
        return Optional.ofNullable(villagerProfiles.get(villager));
    }

    public long actionCounter(String key) { return Math.max(0L, actionCounters.getOrDefault(key, 0L)); }

    public void addActionCounter(String key, long amount) {
        if (fromTheFuture || amount <= 0L) return;
        actionCounters.merge(key, amount, (a, b) -> Math.max(0L, a + b));
        setDirty();
    }

    public long treasuryBalance(String key, long initialBalance) {
        if (!villageTreasuries.containsKey(key) && !fromTheFuture) {
            villageTreasuries.put(key, Math.max(0L, initialBalance));
            setDirty();
        }
        return Math.max(0L, villageTreasuries.getOrDefault(key, 0L));
    }

    public boolean withdrawTreasury(String key, long amount, long initialBalance) {
        if (fromTheFuture || amount < 0L) return false;
        long balance = treasuryBalance(key, initialBalance);
        if (balance < amount) return false;
        villageTreasuries.put(key, balance - amount);
        setDirty();
        return true;
    }

    public boolean hasTransactionReceipt(UUID id) { return transactionReceipts.contains(id); }

    public boolean recordTransactionReceipt(UUID id) {
        if (id == null || fromTheFuture || !transactionReceipts.add(id)) return false;
        while (transactionReceipts.size() > 4096) {
            transactionReceipts.remove(transactionReceipts.iterator().next());
        }
        setDirty();
        return true;
    }

    public void pruneActionCounters(long currentDay) {
        int before = actionCounters.size();
        actionCounters.keySet().removeIf(key -> {
            String[] parts = key.split(":", 4);
            if (parts.length < 3 || !"mug".equals(parts[0])) return false;
            try { return Long.parseLong(parts[2]) < currentDay - 7L; }
            catch (NumberFormatException ignored) { return false; }
        });
        if (actionCounters.size() != before) setDirty();
    }

    // --- integration outbox ---

    /**
     * Queues one cross-mod write. Call this in the <b>same dirty cycle</b> as the authoritative Crime
     * change it describes: that is the whole guarantee — the two either both survive a crash, or
     * neither does.
     *
     * @return false when the queue is full, in which case the caller has still committed its own
     *         change. Refusing the queue entry is always preferable to refusing the crime.
     */
    public boolean enqueueOperation(CrimeIntegrationOperation operation) {
        if (operation == null || fromTheFuture || outbox.containsKey(operation.operationId())) {
            return operation != null && outbox.containsKey(operation.operationId());
        }
        if (outbox.size() >= MAX_PENDING_OPERATIONS) {
            return false;
        }
        outbox.put(operation.operationId(), operation);
        setDirty();
        return true;
    }

    public void updateOperation(CrimeIntegrationOperation operation) {
        if (operation == null || fromTheFuture) {
            return;
        }
        if (operation.status() == CrimeIntegrationOperation.Status.DEAD_LETTER) {
            outbox.remove(operation.operationId());
            deadLetters.add(operation);
            while (deadLetters.size() > MAX_DEAD_LETTERS) {
                deadLetters.remove(0);
            }
        } else if (operation.status() == CrimeIntegrationOperation.Status.COMPLETE) {
            outbox.remove(operation.operationId());
        } else {
            outbox.put(operation.operationId(), operation);
        }
        setDirty();
    }

    /** Pending operations whose backoff has elapsed, oldest first, capped at {@code budget}. */
    public List<CrimeIntegrationOperation> dueOperations(long gameTime, int budget) {
        List<CrimeIntegrationOperation> due = new ArrayList<>();
        for (CrimeIntegrationOperation operation : outbox.values()) {
            if (operation.dueAt(gameTime)) {
                due.add(operation);
            }
        }
        due.sort(Comparator.comparingLong(CrimeIntegrationOperation::createdGameTime));
        return due.size() <= budget ? due : new ArrayList<>(due.subList(0, Math.max(0, budget)));
    }

    public int pendingOperationCount() {
        return outbox.size();
    }

    public int deadLetterCount() {
        return deadLetters.size();
    }

    public List<CrimeIntegrationOperation> deadLetters() {
        return new ArrayList<>(deadLetters);
    }

    /** Moves a dead letter back to pending, for {@code /crime outbox retry}. */
    public boolean reviveDeadLetter(UUID operationId, long gameTime) {
        if (fromTheFuture) {
            return false;
        }
        for (int i = 0; i < deadLetters.size(); i++) {
            CrimeIntegrationOperation operation = deadLetters.get(i);
            if (operation.operationId().equals(operationId)) {
                deadLetters.remove(i);
                outbox.put(operationId, CrimeIntegrationOperation.create(operationId, operation.target(),
                        operation.playerId(), operation.crimeRecordId(), operation.action(),
                        operation.payload(), operation.createdGameTime()));
                setDirty();
                return true;
            }
        }
        return false;
    }

    public boolean dropDeadLetter(UUID operationId) {
        if (deadLetters.removeIf(operation -> operation.operationId().equals(operationId))) {
            setDirty();
            return true;
        }
        return false;
    }

    // --- dedupe store ---

    public Optional<DedupeEntry> dedupe(UUID player, String key) {
        Map<String, DedupeEntry> perPlayer = dedupe.get(player);
        return perPlayer == null ? Optional.empty() : Optional.ofNullable(perPlayer.get(key));
    }

    /** Remembers an applied mutation so a replay returns this answer instead of applying it again. */
    public void rememberDedupe(UUID player, DedupeEntry entry) {
        if (player == null || entry == null || entry.key().isEmpty() || fromTheFuture) {
            return;
        }
        Map<String, DedupeEntry> perPlayer = dedupe.computeIfAbsent(player, k -> new LinkedHashMap<>());
        perPlayer.remove(entry.key()); // re-insert so eviction order is genuinely least-recently-written
        perPlayer.put(entry.key(), entry);
        while (perPlayer.size() > MAX_DEDUPE_PER_PLAYER) {
            perPlayer.remove(perPlayer.keySet().iterator().next());
        }
        setDirty();
    }

    /** Drops expired entries. Called on a bounded interval, never per tick. */
    public int pruneDedupe(long gameTime) {
        int removed = 0;
        for (Map<String, DedupeEntry> perPlayer : dedupe.values()) {
            var iterator = perPlayer.entrySet().iterator();
            while (iterator.hasNext()) {
                if (iterator.next().getValue().expired(gameTime)) {
                    iterator.remove();
                    removed++;
                }
            }
        }
        dedupe.entrySet().removeIf(entry -> entry.getValue().isEmpty());
        if (removed > 0) {
            setDirty();
        }
        return removed;
    }

    // --- persistence ---

    @Override
    public CompoundTag save(CompoundTag tag) {
        // A store from a newer jar was never parsed; hand it back exactly as found rather than
        // overwriting it with what this build happens to understand.
        if (fromTheFuture && reserved.contains(FUTURE_KEY, Tag.TAG_COMPOUND)) {
            return reserved.getCompound(FUTURE_KEY).copy();
        }

        tag.putInt(CrimeDataMigrations.TAG_SCHEMA, CrimeDataMigrations.CURRENT_SCHEMA);

        CompoundTag villages = new CompoundTag();
        villageReputation.forEach((community, players) -> {
            CompoundTag perPlayer = new CompoundTag();
            players.forEach((uuid, delta) -> perPlayer.putInt(uuid.toString(), delta));
            villages.put(community.asString(), perPlayer);
        });
        tag.put("villageReputation", villages);

        ListTag ledgerList = new ListTag();
        for (CrimeRecord record : ledger.values()) {
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

        CompoundTag profileTag = new CompoundTag();
        villagerProfiles.forEach((uuid, profile) -> profileTag.put(uuid.toString(), profile.save()));
        tag.put("villagerProfiles", profileTag);
        CompoundTag counterTag = new CompoundTag();
        actionCounters.forEach(counterTag::putLong);
        tag.put("actionCounters", counterTag);
        CompoundTag treasuryTag = new CompoundTag();
        villageTreasuries.forEach(treasuryTag::putLong);
        tag.put("villageTreasuries", treasuryTag);
        ListTag receiptTag = new ListTag();
        transactionReceipts.forEach(id -> {
            CompoundTag receipt = new CompoundTag();
            receipt.putUUID("id", id);
            receiptTag.add(receipt);
        });
        tag.put("transactionReceipts", receiptTag);

        ListTag outboxList = new ListTag();
        outbox.values().forEach(operation -> outboxList.add(operation.save()));
        tag.put("outbox", outboxList);
        ListTag deadList = new ListTag();
        deadLetters.forEach(operation -> deadList.add(operation.save()));
        tag.put("deadLetters", deadList);

        CompoundTag dedupeTag = new CompoundTag();
        dedupe.forEach((player, entries) -> {
            CompoundTag perPlayer = new CompoundTag();
            entries.forEach((key, entry) -> perPlayer.put(key, entry.save()));
            dedupeTag.put(player.toString(), perPlayer);
        });
        tag.put("dedupe", dedupeTag);

        // Re-emit reserved later-phase slots untouched (bounties, plus any stashed-for-forward-compat tag).
        for (String key : reserved.getAllKeys()) {
            tag.put(key, reserved.get(key).copy());
        }
        return tag;
    }

    public static CrimeWorldData load(CompoundTag raw) {
        CrimeWorldData data = new CrimeWorldData();

        int schema = CrimeDataMigrations.schemaOf(raw);
        if (schema > CrimeDataMigrations.CURRENT_SCHEMA) {
            // Written by a newer jar. Parsing it would mean guessing at structures we do not know,
            // and saving what we guessed would destroy them. Park the whole thing and refuse writes.
            data.fromTheFuture = true;
            data.reserved.put(FUTURE_KEY, raw.copy());
            McaCrime.LOGGER.warn("MCA: Crime data is schema {} but this build understands {}. The store has been "
                            + "left completely untouched and no crime state will be written this session. "
                            + "Update MCA: Crime, or restore a backup taken before the newer version ran.",
                    schema, CrimeDataMigrations.CURRENT_SCHEMA);
            return data;
        }

        CompoundTag tag = CrimeDataMigrations.migrate(raw);

        CompoundTag villages = tag.getCompound("villageReputation");
        for (String communityKey : villages.getAllKeys()) {
            Optional<CrimeCommunityKey> community = CrimeCommunityKey.tryParse(communityKey);
            if (community.isEmpty()) {
                continue; // skip a malformed community key
            }
            CompoundTag perPlayer = villages.getCompound(communityKey);
            Map<UUID, Integer> players = new LinkedHashMap<>();
            for (String uuidKey : perPlayer.getAllKeys()) {
                try {
                    players.put(UUID.fromString(uuidKey), perPlayer.getInt(uuidKey));
                } catch (IllegalArgumentException e) {
                    // skip a malformed UUID key
                }
            }
            if (!players.isEmpty()) {
                data.villageReputation.put(community.get(), players);
            }
        }

        ListTag ledgerList = tag.getList("ledger", Tag.TAG_COMPOUND);
        int repairedDuplicates = 0;
        for (int i = 0; i < ledgerList.size(); i++) {
            CrimeRecord record;
            try {
                record = CrimeRecord.load(ledgerList.getCompound(i));
            } catch (RuntimeException e) {
                continue; // skip a malformed ledger entry rather than dropping the whole store
            }
            if (data.ledger.containsKey(record.id())) {
                // Two rows claiming one id: keep the first as canonical and give the later one a
                // derived id, so both survive and the ledger stays addressable. Derived rather than
                // random so the same file always repairs to the same ids.
                record = record
                        .withId(deriveRepairId(record.id(), ++repairedDuplicates))
                        .withContext(CrimeContext.DUPLICATE_ID_REPAIRED, record.id().toString());
            }
            data.ledger.put(record.id(), record);
            data.byOffender.computeIfAbsent(record.offender(), k -> new ArrayList<>()).add(record.id());
        }
        if (repairedDuplicates > 0) {
            McaCrime.LOGGER.warn("MCA: Crime found {} crime record(s) sharing an id with an earlier record. "
                    + "The first of each was kept as-is and the later ones re-identified deterministically; "
                    + "their original id is recorded in each record's context.", repairedDuplicates);
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

        CompoundTag profileTag = tag.getCompound("villagerProfiles");
        for (String key : profileTag.getAllKeys()) {
            try {
                VillagerCrimeProfile profile = VillagerCrimeProfile.load(profileTag.getCompound(key));
                if (profile != null) data.villagerProfiles.put(UUID.fromString(key), profile);
            } catch (RuntimeException ignored) {
                // Skip one malformed profile without losing unrelated economy state.
            }
        }
        CompoundTag counterTag = tag.getCompound("actionCounters");
        counterTag.getAllKeys().forEach(key -> data.actionCounters.put(key, Math.max(0L, counterTag.getLong(key))));
        CompoundTag treasuryTag = tag.getCompound("villageTreasuries");
        treasuryTag.getAllKeys().forEach(key -> data.villageTreasuries.put(key, Math.max(0L, treasuryTag.getLong(key))));
        ListTag receiptTag = tag.getList("transactionReceipts", Tag.TAG_COMPOUND);
        for (int i = 0; i < receiptTag.size(); i++) {
            CompoundTag receipt = receiptTag.getCompound(i);
            if (receipt.hasUUID("id")) data.transactionReceipts.add(receipt.getUUID("id"));
        }

        ListTag outboxList = tag.getList("outbox", Tag.TAG_COMPOUND);
        for (int i = 0; i < outboxList.size(); i++) {
            CrimeIntegrationOperation operation = CrimeIntegrationOperation.load(outboxList.getCompound(i));
            if (operation != null) {
                data.outbox.put(operation.operationId(), operation);
            }
        }
        ListTag deadList = tag.getList("deadLetters", Tag.TAG_COMPOUND);
        for (int i = 0; i < deadList.size(); i++) {
            CrimeIntegrationOperation operation = CrimeIntegrationOperation.load(deadList.getCompound(i));
            if (operation != null) {
                data.deadLetters.add(operation);
            }
        }

        CompoundTag dedupeTag = tag.getCompound("dedupe");
        for (String playerKey : dedupeTag.getAllKeys()) {
            UUID player;
            try {
                player = UUID.fromString(playerKey);
            } catch (IllegalArgumentException e) {
                continue;
            }
            CompoundTag perPlayer = dedupeTag.getCompound(playerKey);
            Map<String, DedupeEntry> entries = new LinkedHashMap<>();
            for (String key : perPlayer.getAllKeys()) {
                try {
                    entries.put(key, DedupeEntry.load(perPlayer.getCompound(key)));
                } catch (RuntimeException e) {
                    // skip a malformed dedupe entry
                }
            }
            if (!entries.isEmpty()) {
                data.dedupe.put(player, entries);
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

    /**
     * A stable replacement id for a duplicated record. Derived from the original plus its ordinal, so
     * loading the same file twice always produces the same repair.
     */
    private static UUID deriveRepairId(UUID original, int ordinal) {
        return UUID.nameUUIDFromBytes((original + ":dup:" + ordinal).getBytes(StandardCharsets.UTF_8));
    }
}
