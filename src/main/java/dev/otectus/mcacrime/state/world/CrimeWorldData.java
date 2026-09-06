package dev.otectus.mcacrime.state.world;

import dev.otectus.mcacrime.McaCrime;
import dev.otectus.mcacrime.api.model.CrimeCommunityKey;
import dev.otectus.mcacrime.captivity.CustodyRecord;
import dev.otectus.mcacrime.economy.account.TransactionReceipt;
import dev.otectus.mcacrime.economy.fence.FenceStockRecord;
import dev.otectus.mcacrime.integration.CrimeIntegrationOperation;
import dev.otectus.mcacrime.integration.DedupeEntry;
import dev.otectus.mcacrime.jail.HoldingCell;
import dev.otectus.mcacrime.jail.JailAnchor;
import dev.otectus.mcacrime.ledger.CrimeContext;
import dev.otectus.mcacrime.ledger.CrimeRecord;
import dev.otectus.mcacrime.ledger.Warrant;
import dev.otectus.mcacrime.memory.CrimeObservation;
import dev.otectus.mcacrime.memory.CrimeReport;
import dev.otectus.mcacrime.memory.ReportState;
import dev.otectus.mcacrime.memory.VillagerCrimeProfile;
import dev.otectus.mcacrime.ransom.RansomState;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

import javax.annotation.Nullable;

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
    private static final String[] RESERVED_KEYS = {"bounties", "archives"};

    /** Where a whole from-the-future store is parked rather than parsed. */
    private static final String FUTURE_KEY = "__future";

    /** Global ceiling on pending integration work, so a permanently broken bridge cannot grow forever. */
    private static final int MAX_PENDING_OPERATIONS = 2000;
    /** Ceiling on retained dead letters. */
    private static final int MAX_DEAD_LETTERS = 200;
    /** Ceiling on remembered dedupe keys per player. */
    private static final int MAX_DEDUPE_PER_PLAYER = 64;
    /**
     * Pending observations one NPC may be carrying at once (spec §11.1). Eight is not an arbitrary
     * round number: it is the point past which a villager is no longer a witness to anything in
     * particular, and letting the list grow would turn one busy village into an unbounded per-entity
     * store that has to be walked every time anybody looks at them.
     */
    private static final int MAX_PENDING_OBSERVATIONS_PER_OBSERVER = 8;
    /** Global ceiling on stored observations, oldest-resolved-first evicted. */
    private static final int MAX_OBSERVATIONS = 4096;
    /** Global ceiling on stored reports. */
    private static final int MAX_REPORTS = 2048;
    /** Ceilings on the 0.5.1 collections. A malformed or hostile file must not allocate without bound. */
    private static final int MAX_CRIMINAL_VILLAGERS = 4096;
    private static final int MAX_STOLEN_GOODS = 4096;
    private static final int MAX_WARRANTS = 4096;
    private static final int MAX_BOUNTY_CLAIMS = 8192;
    private static final int MAX_BOUNTY_CONTRACTS = 1024;
    private static final int MAX_FENCE_RESTOCK_DAYS = 4096;
    private static final int MAX_FENCE_STOCK = 4096;
    /** Ceilings on the 0.6.0 collections. */
    private static final int MAX_CUSTODY = 4096;
    private static final int MAX_HOLDING_CELLS = 1024;
    private static final int MAX_TRANSACTIONS = 4096;
    private static final int MAX_PROPERTY_ESCROW = 4096;
    /**
     * How many unreadable rows are kept for an operator to look at. Small on purpose: quarantine is a
     * diagnostic, not a second copy of the store, and a systematically corrupt file would otherwise
     * make the save file grow every time it was loaded.
     */
    private static final int MAX_QUARANTINE = 256;
    /** Ticks in a Minecraft day, the unit terminal-receipt retention is expressed in. */
    private static final long TICKS_PER_DAY = 24000L;
    /** How long a finished receipt is kept before the maintenance sweep forgets it. */
    private static final long TRANSACTION_RETENTION_DAYS = 30L;

    /** community -> (playerUuid -> standing delta). LinkedHashMap for stable save ordering. */
    private final Map<CrimeCommunityKey, Map<UUID, Integer>> villageReputation = new LinkedHashMap<>();
    /** The crime ledger (§2.2), keyed by record id; iteration order is append order. */
    private final Map<UUID, CrimeRecord> ledger = new LinkedHashMap<>();
    /** offender -> record ids, rebuilt on load. Not serialised: it is derivable from the ledger. */
    private final Map<UUID, List<UUID>> byOffender = new LinkedHashMap<>();
    /** Assigned jail anchors (§7.4 command-based assignment). */
    private final List<JailAnchor> jailAnchors = new ArrayList<>();
    /** Cells this mod built, by prisoner. A player can only be held in one place at a time. */
    private final Map<UUID, HoldingCell> holdingCells = new LinkedHashMap<>();
    /** The custody table (§2.3): captive UUID -> record. LinkedHashMap for stable save ordering. */
    private final Map<UUID, CustodyRecord> custody = new LinkedHashMap<>();
    /** Active ransom demands (§8.5), keyed by victim UUID (one open demand per victim). */
    private final Map<UUID, RansomState> ransoms = new LinkedHashMap<>();
    /** Ransom anti-farm stamps: key ("victim:uuid" / "payer:uuid" / "village:id") -> last demand game-time. */
    private final Map<String, Long> ransomCooldowns = new LinkedHashMap<>();
    /** Villager purse, recovery, and offender memory, independent of chunk loading. */
    private final Map<UUID, VillagerCrimeProfile> villagerProfiles = new LinkedHashMap<>();
    /** Who saw what (§12.1), keyed by observation id; insertion order is observation order. */
    private final Map<UUID, CrimeObservation> observations = new LinkedHashMap<>();
    /** observer -> observation ids. Rebuilt on load; derivable, so never serialised. */
    private final Map<UUID, List<UUID>> observationsByObserver = new LinkedHashMap<>();
    /** Filed reports (§12.3), keyed by report id. */
    private final Map<UUID, CrimeReport> reports = new LinkedHashMap<>();
    /** suspect -> report ids. Rebuilt on load. */
    private final Map<UUID, List<UUID>> reportsBySuspect = new LinkedHashMap<>();
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
    /** Criminal occupations (§"criminal jobs"), keyed by villager. Authoritative over MCA's profession. */
    private final Map<UUID, CriminalVillagerRecord> criminalVillagers = new LinkedHashMap<>();
    /** Stolen goods awaiting recovery, keyed by transaction id. */
    private final Map<UUID, StolenGoodsRecord> stolenGoods = new LinkedHashMap<>();
    /** thief -> transaction ids. Rebuilt on load; derivable, so never serialised. */
    private final Map<UUID, List<UUID>> stolenByThief = new LinkedHashMap<>();
    /** Open and recently-closed warrants, keyed by offender. One warrant per offender at a time. */
    private final Map<UUID, Warrant> warrants = new LinkedHashMap<>();
    /** Paid bounties, keyed by {@code target/warrantId/revision}. Append-only within the retention window. */
    private final Map<String, BountyClaimRecord> bountyClaims = new LinkedHashMap<>();
    /** Posted contracts, keyed by contract id. Survives the quest mod being uninstalled. */
    private final Map<UUID, BountyContractRecord> bountyContracts = new LinkedHashMap<>();
    /** fence -> the day its stock was last rolled. */
    private final Map<UUID, Long> fenceRestockDay = new LinkedHashMap<>();
    /** How much of its stock each fence has sold this stocking (0.6.0). Keyed by the fence's UUID. */
    private final Map<UUID, FenceStockRecord> fenceStock = new LinkedHashMap<>();
    /** Tables that have already said they are full. One line per table per session, not per refusal. */
    private final Set<String> capacityReported = new LinkedHashSet<>();
    /** Money that moved, and how far it got (0.6.0, spec §4.5). Keyed by transaction id. */
    private final Map<UUID, TransactionReceipt> transactions = new LinkedHashMap<>();
    /** Property owed to a player who could not take delivery of it (0.6.0). Keyed by lot id. */
    private final Map<UUID, PropertyLot> propertyEscrow = new LinkedHashMap<>();
    /**
     * Cells whose blocks were not all put back, keyed by prisoner (0.6.0).
     *
     * <p>Each entry is the original cell narrowed to the positions still standing, so the retry needs
     * nothing but the entry itself. A cell only lands here when demolition ran and part of it was out
     * of reach — an unloaded chunk, almost always — and it leaves again the moment a retry restores
     * the rest.
     */
    private final Map<UUID, HoldingCell> pendingCellRestorations = new LinkedHashMap<>();
    /**
     * Rows that could not be read, kept verbatim beside the reason (0.6.0).
     *
     * <p>"Skip the malformed entry" was always half a policy: it kept the store loadable and threw the
     * evidence away, so a player whose case vanished had nothing to appeal to and nobody could tell a
     * corrupt file from a bug in the loader. The row is now written back out untouched.
     */
    private final List<CompoundTag> quarantine = new ArrayList<>();
    /** Quarantine rows dropped because the list was full. Reported once per load. */
    private int quarantineDropped;
    /** Verbatim copy of any reserved later-phase tags found on disk, re-emitted untouched on save. */
    private final CompoundTag reserved = new CompoundTag();

    /** True when the file came from a newer jar and nothing was parsed. */
    private boolean fromTheFuture;

    /**
     * True when reading the file threw and what is in memory is not what is on disk.
     *
     * <p>Treated exactly like a from-the-future store, because the consequence is the same: saving
     * would replace a file whose contents this build failed to understand with a partial reading of
     * it. The difference is only in what to tell the operator.
     */
    private boolean loadFailed;

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

    /** True when this store could not be read and is being carried through untouched. */
    public boolean isLoadFailed() {
        return loadFailed;
    }

    /**
     * Whether this store refuses every write. The backstop under {@link ServerMutationGate}: callers
     * are expected to ask the gate before they begin, and this is what makes forgetting to harmless.
     */
    private boolean frozen() {
        return fromTheFuture || loadFailed;
    }

    /**
     * Whether one more entry would put {@code table} over its ceiling.
     *
     * <p>Replacing a key already present is always allowed: a full table has to stop new entries
     * arriving, not stop the ones already in it from being updated. Evicting to make room is
     * deliberately not an option — the entries here are custody records, cells and stock counts, and
     * silently forgetting one is how a prisoner ends up held by nothing or a fence ends up with
     * infinite goods.
     */
    private <K, V> boolean atCapacity(Map<K, V> table, K key, int max, String noun) {
        if (table.containsKey(key) || table.size() < max) {
            return false;
        }
        if (capacityReported.add(noun)) {
            McaCrime.LOGGER.warn("MCA: Crime is holding the maximum of {} {} record(s); further ones are "
                    + "being refused rather than evicting an existing entry.", max, noun);
        }
        return true;
    }

    // --- per-community standing (§2.5) ---

    public int reputation(CrimeCommunityKey community, UUID player) {
        Map<UUID, Integer> village = villageReputation.get(community);
        return village == null ? 0 : village.getOrDefault(player, 0);
    }

    public void addReputation(CrimeCommunityKey community, UUID player, int delta) {
        if (community == null || delta == 0 || frozen()) {
            return;
        }
        villageReputation.computeIfAbsent(community, k -> new LinkedHashMap<>())
                .merge(player, delta, Integer::sum);
        setDirty();
    }

    /**
     * Every community this world has ever recorded standing for. Used by global crime propagation,
     * which by definition needs the set of villages that already know the player — propagating to a
     * village nobody has ever met would create standing out of nothing.
     */
    public Collection<CrimeCommunityKey> communities() {
        return List.copyOf(villageReputation.keySet());
    }

    /** Overwrites rather than accumulates — used to mirror a companion mod's canonical score. */
    public void setReputation(CrimeCommunityKey community, UUID player, int value) {
        if (community == null || frozen()) {
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
        if (record == null || frozen() || ledger.containsKey(record.id())) {
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
        if (record == null || frozen() || !ledger.containsKey(record.id())) {
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

    /**
     * The still-actionable cases charged under {@code sentenceId}, oldest first.
     *
     * <p>This is the selection a release settles. It is deliberately narrower than
     * {@link #actionableFor(UUID)}: a case committed after the sentence began -- by an escapee, or by
     * a prisoner released and re-arrested -- was never part of what was being served, and closing it
     * with the term would hand out an amnesty nobody sentenced.
     */
    public List<CrimeRecord> casesForSentence(UUID offender, UUID sentenceId) {
        if (offender == null || sentenceId == null) {
            return new ArrayList<>();
        }
        List<CrimeRecord> out = new ArrayList<>();
        for (CrimeRecord record : actionableFor(offender)) {
            if (sentenceId.equals(record.sentenceId())) {
                out.add(record);
            }
        }
        return out;
    }

    /**
     * Charges every currently actionable, unbound case against {@code offender} under
     * {@code sentenceId}. Cases already bound to a sentence -- including this one -- are left alone,
     * so a re-jailing cannot steal another sentence's charges.
     *
     * @return the ids actually bound
     */
    public List<UUID> bindSentence(UUID offender, UUID sentenceId, long now) {
        return bind(offender, sentenceId, now, false);
    }

    /**
     * The same binding, marked as an inference rather than a fact (0.6.0).
     *
     * <p>A sentence handed down before cases could name one has no record of what it was for, and
     * nothing in a saved world can reconstruct it. Assuming it covers the charges standing against the
     * prisoner is the only reading that lets an in-flight sentence still mean something on release;
     * {@link CrimeContext#LEGACY_SENTENCE_INFERRED} is how the ledger admits that is what happened.
     */
    public List<UUID> bindLegacySentence(UUID offender, UUID sentenceId, long now) {
        return bind(offender, sentenceId, now, true);
    }

    private List<UUID> bind(UUID offender, UUID sentenceId, long now, boolean inferred) {
        if (offender == null || sentenceId == null || frozen()) {
            return new ArrayList<>();
        }
        List<UUID> bound = new ArrayList<>();
        for (CrimeRecord record : actionableFor(offender)) {
            if (record.sentenceId() != null) {
                continue;
            }
            CrimeRecord charged = record.withSentence(sentenceId);
            if (inferred) {
                charged = charged.withContext(CrimeContext.LEGACY_SENTENCE_INFERRED, Long.toString(now));
            }
            if (replaceRecord(charged)) {
                bound.add(record.id());
            }
        }
        return bound;
    }

    public int ledgerSize() {
        return ledger.size();
    }

    // --- jail anchors (§7.4) ---

    public void addJailAnchor(JailAnchor anchor) {
        if (frozen()) {
            return;
        }
        jailAnchors.add(anchor);
        setDirty();
    }

    public List<JailAnchor> jailAnchors() {
        return new ArrayList<>(jailAnchors);
    }

    // --- holding cells (§7.4, built rather than assigned) ---

    /**
     * Records a cell this mod built. Keyed by prisoner, so re-arresting somebody who already has a
     * cell standing replaces the record rather than accumulating cages.
     */
    public CapacityResult putHoldingCell(HoldingCell cell) {
        if (cell == null || cell.prisoner() == null || frozen()) {
            return CapacityResult.FULL;
        }
        if (atCapacity(holdingCells, cell.prisoner(), MAX_HOLDING_CELLS, "holding cell")) {
            return CapacityResult.FULL;
        }
        holdingCells.put(cell.prisoner(), cell);
        setDirty();
        return CapacityResult.OK;
    }

    @Nullable
    public HoldingCell holdingCellFor(UUID prisoner) {
        return prisoner == null ? null : holdingCells.get(prisoner);
    }

    @Nullable
    public HoldingCell removeHoldingCell(UUID prisoner) {
        if (prisoner == null || frozen()) {
            return null;
        }
        HoldingCell removed = holdingCells.remove(prisoner);
        if (removed != null) {
            setDirty();
        }
        return removed;
    }

    /** Every standing cell, for the expiry sweep and for {@code /crime debug}. */
    public List<HoldingCell> holdingCells() {
        return new ArrayList<>(holdingCells.values());
    }

    // --- custody table (§2.3) ---

    /** Inserts/replaces the record for its captive. Idempotent by captive UUID (replay-safe). */
    public CapacityResult putCustody(CustodyRecord record) {
        if (record == null || record.getCaptive() == null || frozen()) {
            return CapacityResult.FULL;
        }
        if (atCapacity(custody, record.getCaptive(), MAX_CUSTODY, "custody")) {
            return CapacityResult.FULL;
        }
        custody.put(record.getCaptive(), record);
        setDirty();
        return CapacityResult.OK;
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
        if (state == null || state.getVictim() == null || frozen()) {
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
        if (frozen()) {
            return;
        }
        ransomCooldowns.put(key, gameTime);
        setDirty();
    }

    // --- action economy + villager memory (0.3.0) ---

    public VillagerCrimeProfile villagerProfile(UUID villager, Supplier<VillagerCrimeProfile> factory) {
        VillagerCrimeProfile profile = villagerProfiles.get(villager);
        if (profile == null && !frozen()) {
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

    /**
     * Overwrites a counter rather than adding to it.
     *
     * <p>The counters started life as daily totals, which only ever grow. The criminal-job sweep
     * stores a <em>day number</em> per village under the same map, and a day that only ever increased
     * by addition would put a village on a cooldown that never ended.
     */
    public void setActionCounter(String key, long value) {
        if (frozen() || key == null) return;
        actionCounters.put(key, Math.max(0L, value));
        setDirty();
    }

    public void addActionCounter(String key, long amount) {
        if (frozen() || amount <= 0L) return;
        actionCounters.merge(key, amount, (a, b) -> Math.max(0L, a + b));
        setDirty();
    }

    public long treasuryBalance(String key, long initialBalance) {
        if (!villageTreasuries.containsKey(key) && !frozen()) {
            villageTreasuries.put(key, Math.max(0L, initialBalance));
            setDirty();
        }
        return Math.max(0L, villageTreasuries.getOrDefault(key, 0L));
    }

    public boolean withdrawTreasury(String key, long amount, long initialBalance) {
        if (frozen() || amount < 0L) return false;
        long balance = treasuryBalance(key, initialBalance);
        if (balance < amount) return false;
        villageTreasuries.put(key, balance - amount);
        setDirty();
        return true;
    }

    public boolean hasTransactionReceipt(UUID id) { return transactionReceipts.contains(id); }

    public boolean recordTransactionReceipt(UUID id) {
        if (id == null || frozen() || !transactionReceipts.add(id)) return false;
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

    // --- observations and reports (§12) ---

    /**
     * Stores one observation. Idempotent by observation id, so a replayed detection cannot make a
     * villager remember the same crime twice.
     *
     * @return false when the observer is already carrying the maximum number of pending observations,
     *         in which case nothing is stored. Refusing the ninth is deliberate: the alternative is
     *         either an unbounded per-entity list or silently discarding an older observation the
     *         villager may still be walking to a guard about.
     */
    public boolean addObservation(CrimeObservation observation) {
        if (observation == null || frozen() || observations.containsKey(observation.observationId())) {
            return false;
        }
        if (observation.pending() && pendingObservationCount(observation.observerId())
                >= MAX_PENDING_OBSERVATIONS_PER_OBSERVER) {
            return false;
        }
        observations.put(observation.observationId(), observation);
        observationsByObserver.computeIfAbsent(observation.observerId(), k -> new ArrayList<>())
                .add(observation.observationId());
        evictOldestSettledObservations();
        setDirty();
        return true;
    }

    /** Replaces an observation in place. Does nothing for an unknown id. */
    public boolean replaceObservation(CrimeObservation observation) {
        if (observation == null || frozen() || !observations.containsKey(observation.observationId())) {
            return false;
        }
        observations.put(observation.observationId(), observation);
        setDirty();
        return true;
    }

    public Optional<CrimeObservation> observation(UUID observationId) {
        return Optional.ofNullable(observations.get(observationId));
    }

    /** Everything this observer knows, oldest first — the order they would report it in. */
    public List<CrimeObservation> observationsBy(UUID observer) {
        List<UUID> ids = observationsByObserver.get(observer);
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        List<CrimeObservation> out = new ArrayList<>(ids.size());
        for (UUID id : ids) {
            CrimeObservation observation = observations.get(id);
            if (observation != null) {
                out.add(observation);
            }
        }
        return out;
    }

    /** This observer's undelivered observations, oldest first. */
    public List<CrimeObservation> pendingObservationsBy(UUID observer) {
        List<CrimeObservation> out = new ArrayList<>();
        for (CrimeObservation observation : observationsBy(observer)) {
            if (observation.pending()) {
                out.add(observation);
            }
        }
        return out;
    }

    public int pendingObservationCount(UUID observer) {
        return pendingObservationsBy(observer).size();
    }

    public int observationCount() {
        return observations.size();
    }

    /** Files a report. Idempotent by report id. */
    public boolean addReport(CrimeReport report) {
        if (report == null || frozen() || reports.containsKey(report.reportId())) {
            return false;
        }
        reports.put(report.reportId(), report);
        reportsBySuspect.computeIfAbsent(report.suspectId(), k -> new ArrayList<>()).add(report.reportId());
        while (reports.size() > MAX_REPORTS) {
            UUID oldest = reports.keySet().iterator().next();
            CrimeReport dropped = reports.remove(oldest);
            if (dropped != null) {
                List<UUID> bySuspect = reportsBySuspect.get(dropped.suspectId());
                if (bySuspect != null) {
                    bySuspect.remove(oldest);
                    if (bySuspect.isEmpty()) {
                        reportsBySuspect.remove(dropped.suspectId());
                    }
                }
            }
        }
        setDirty();
        return true;
    }

    /** Reports naming this suspect, oldest first. */
    public List<CrimeReport> reportsAgainst(UUID suspect) {
        List<UUID> ids = reportsBySuspect.get(suspect);
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        List<CrimeReport> out = new ArrayList<>(ids.size());
        for (UUID id : ids) {
            CrimeReport report = reports.get(id);
            if (report != null) {
                out.add(report);
            }
        }
        return out;
    }

    public int reportCount() {
        return reports.size();
    }

    /**
     * Ages out observations and reports whose statute has run. Called on a bounded interval, never per
     * tick. Expiry marks rather than deletes: "this villager saw it and never told anybody in time" is
     * a different fact from "nobody saw it", and dialogue and the case file both need the difference.
     */
    public int pruneObservations(long gameTime) {
        int changed = 0;
        for (Map.Entry<UUID, CrimeObservation> entry : observations.entrySet()) {
            CrimeObservation observation = entry.getValue();
            if (observation.pending() && observation.expired(gameTime)) {
                entry.setValue(observation.withReportState(ReportState.EXPIRED));
                changed++;
            }
        }
        int before = reports.size();
        reports.values().removeIf(report -> report.expired(gameTime));
        if (reports.size() != before) {
            rebuildReportIndex();
            changed += before - reports.size();
        }
        if (changed > 0) {
            setDirty();
        }
        return changed;
    }

    /**
     * Drops the oldest observations that are no longer pending once the global ceiling is passed. Only
     * settled rows are evicted: a pending observation is somebody currently walking to a guard, and
     * deleting it would make the crime quietly unreportable.
     */
    private void evictOldestSettledObservations() {
        if (observations.size() <= MAX_OBSERVATIONS) {
            return;
        }
        var iterator = observations.entrySet().iterator();
        while (iterator.hasNext() && observations.size() > MAX_OBSERVATIONS) {
            Map.Entry<UUID, CrimeObservation> entry = iterator.next();
            if (entry.getValue().pending()) {
                continue;
            }
            List<UUID> byObserver = observationsByObserver.get(entry.getValue().observerId());
            if (byObserver != null) {
                byObserver.remove(entry.getKey());
                if (byObserver.isEmpty()) {
                    observationsByObserver.remove(entry.getValue().observerId());
                }
            }
            iterator.remove();
        }
    }

    private void rebuildReportIndex() {
        reportsBySuspect.clear();
        reports.forEach((id, report) ->
                reportsBySuspect.computeIfAbsent(report.suspectId(), k -> new ArrayList<>()).add(id));
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
        if (operation == null || frozen() || outbox.containsKey(operation.operationId())) {
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
        if (operation == null || frozen()) {
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
        if (frozen()) {
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
        if (player == null || entry == null || entry.key().isEmpty() || frozen()) {
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

    // --- criminal jobs, stolen goods, warrants and bounties (0.5.1) ---

    @Nullable
    public CriminalVillagerRecord criminalVillager(UUID villager) {
        return villager == null ? null : criminalVillagers.get(villager);
    }

    public CapacityResult putCriminalVillager(CriminalVillagerRecord record) {
        if (record == null || frozen()) {
            return CapacityResult.FULL;
        }
        if (atCapacity(criminalVillagers, record.villager(), MAX_CRIMINAL_VILLAGERS, "criminal villager")) {
            return CapacityResult.FULL;
        }
        criminalVillagers.put(record.villager(), record);
        setDirty();
        return CapacityResult.OK;
    }

    public void removeCriminalVillager(UUID villager) {
        if (villager == null || frozen()) {
            return;
        }
        if (criminalVillagers.remove(villager) != null) {
            setDirty();
        }
    }

    /** Every criminal this world knows about. A copy: the sweep iterates it while assigning. */
    public Collection<CriminalVillagerRecord> criminalVillagers() {
        return List.copyOf(criminalVillagers.values());
    }

    @Nullable
    public StolenGoodsRecord stolenGoods(UUID transactionId) {
        return transactionId == null ? null : stolenGoods.get(transactionId);
    }

    public CapacityResult putStolenGoods(StolenGoodsRecord record) {
        if (record == null || frozen()) {
            return CapacityResult.FULL;
        }
        if (atCapacity(stolenGoods, record.transactionId(), MAX_STOLEN_GOODS, "stolen goods")) {
            return CapacityResult.FULL;
        }
        stolenGoods.put(record.transactionId(), record);
        indexStolen(record);
        setDirty();
        return CapacityResult.OK;
    }

    /** Removes one record and keeps the by-thief index in step; returns what was removed. */
    @Nullable
    public StolenGoodsRecord removeStolenGoods(UUID transactionId) {
        if (transactionId == null || frozen()) {
            return null;
        }
        StolenGoodsRecord removed = stolenGoods.remove(transactionId);
        if (removed == null) {
            return null;
        }
        List<UUID> ids = stolenByThief.get(removed.thief());
        if (ids != null) {
            ids.remove(transactionId);
            if (ids.isEmpty()) {
                stolenByThief.remove(removed.thief());
            }
        }
        setDirty();
        return removed;
    }

    /** Everything this thief is currently carrying the provenance of. Never null. */
    public List<StolenGoodsRecord> stolenGoodsByThief(UUID thief) {
        List<UUID> ids = thief == null ? null : stolenByThief.get(thief);
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        List<StolenGoodsRecord> out = new ArrayList<>(ids.size());
        for (UUID id : ids) {
            StolenGoodsRecord record = stolenGoods.get(id);
            if (record != null) {
                out.add(record);
            }
        }
        return out;
    }

    /** Every stolen-goods record in the world. A copy: expiry iterates it while removing from it. */
    public Collection<StolenGoodsRecord> stolenGoods() {
        return List.copyOf(stolenGoods.values());
    }

    @Nullable
    public Warrant warrant(UUID offender) {
        return offender == null ? null : warrants.get(offender);
    }

    public CapacityResult putWarrant(Warrant warrant) {
        if (warrant == null || frozen()) {
            return CapacityResult.FULL;
        }
        if (atCapacity(warrants, warrant.offender(), MAX_WARRANTS, "warrant")) {
            return CapacityResult.FULL;
        }
        warrants.put(warrant.offender(), warrant);
        setDirty();
        return CapacityResult.OK;
    }

    @Nullable
    public BountyClaimRecord bountyClaim(String claimKey) {
        return claimKey == null ? null : bountyClaims.get(claimKey);
    }

    /**
     * Records a claim only if that key has never been claimed. Returns whether it was recorded.
     *
     * <p>This is the whole anti-double-pay mechanism, so it is one operation rather than a
     * check-then-put a caller could interleave: two kills resolving in the same tick must not both
     * see an absent key and both pay.
     */
    public boolean putBountyClaimIfAbsent(String claimKey, BountyClaimRecord record) {
        if (claimKey == null || record == null || frozen() || bountyClaims.containsKey(claimKey)) {
            return false;
        }
        if (atCapacity(bountyClaims, claimKey, MAX_BOUNTY_CLAIMS, "bounty claim")) {
            return false; // refusing to record the claim is refusing to pay it, which is the safe half
        }
        bountyClaims.put(claimKey, record);
        setDirty();
        return true;
    }

    /** Every claim ever paid. A copy: retention expiry iterates it while removing from it. */
    public Map<String, BountyClaimRecord> bountyClaims() {
        return Map.copyOf(bountyClaims);
    }

    /** Forgets one claim. Only retention expiry does this; nothing else may un-pay a bounty. */
    public void removeBountyClaim(String claimKey) {
        if (claimKey == null || frozen()) {
            return;
        }
        if (bountyClaims.remove(claimKey) != null) {
            setDirty();
        }
    }

    /** Every posted contract. A copy: the board iterates it while invalidating. */
    public Collection<BountyContractRecord> bountyContracts() {
        return List.copyOf(bountyContracts.values());
    }

    public CapacityResult putBountyContract(BountyContractRecord contract) {
        if (contract == null || frozen()) {
            return CapacityResult.FULL;
        }
        if (atCapacity(bountyContracts, contract.contractId(), MAX_BOUNTY_CONTRACTS, "bounty contract")) {
            return CapacityResult.FULL;
        }
        bountyContracts.put(contract.contractId(), contract);
        setDirty();
        return CapacityResult.OK;
    }

    public void removeBountyContract(UUID contractId) {
        if (contractId == null || frozen()) {
            return;
        }
        if (bountyContracts.remove(contractId) != null) {
            setDirty();
        }
    }

    /** The day this fence last restocked, or 0 for one that never has. */
    public long fenceRestockDay(UUID fence) {
        return fence == null ? 0L : fenceRestockDay.getOrDefault(fence, 0L);
    }

    /** Every fence's restock day. A copy: the maintenance sweep iterates it while removing from it. */
    public Map<UUID, Long> fenceRestockDays() {
        return Map.copyOf(fenceRestockDay);
    }

    /** Forgets one restock day. Only the maintenance sweep does this, for a villager who is no fence. */
    public void removeFenceRestockDay(UUID fence) {
        if (fence == null || frozen()) {
            return;
        }
        if (fenceRestockDay.remove(fence) != null) {
            setDirty();
        }
    }

    /** This fence's persisted stock for the current stocking, or null for one that has never opened. */
    @Nullable
    public FenceStockRecord getFenceStock(UUID fence) {
        return fence == null ? null : fenceStock.get(fence);
    }

    /**
     * Inserts or replaces one fence's stock, and says whether it was kept.
     *
     * <p>Bounded at {@value #MAX_FENCE_STOCK} fences, refusing the insert rather than evicting an
     * existing one: a fence whose counts were dropped would be a fence with infinite stock again,
     * which is the bug this table exists to close. Updating a fence already in the table is always
     * allowed, so a full table stops new fences from opening rather than breaking the ones trading.
     *
     * @return {@link CapacityResult#FULL} when the table is full and this fence is not already in it
     */
    public CapacityResult putFenceStock(FenceStockRecord record) {
        if (record == null || record.fence() == null || frozen()) {
            return CapacityResult.FULL;
        }
        if (atCapacity(fenceStock, record.fence(), MAX_FENCE_STOCK, "fence stock")) {
            return CapacityResult.FULL;
        }
        fenceStock.put(record.fence(), record);
        setDirty();
        return CapacityResult.OK;
    }

    /** Every fence being tracked. A copy: the maintenance sweep iterates it while removing from it. */
    public Set<UUID> fenceStockIds() {
        return Set.copyOf(fenceStock.keySet());
    }

    /** Forgets one fence's stock. The maintenance sweep does this for a villager who is no fence. */
    public void removeFenceStock(UUID fence) {
        if (fence == null || frozen()) {
            return;
        }
        if (fenceStock.remove(fence) != null) {
            setDirty();
        }
    }

    public void setFenceRestockDay(UUID fence, long day) {
        if (fence == null || frozen()) {
            return;
        }
        fenceRestockDay.put(fence, day);
        setDirty();
    }

    private void indexStolen(StolenGoodsRecord record) {
        List<UUID> ids = stolenByThief.computeIfAbsent(record.thief(), k -> new ArrayList<>());
        if (!ids.contains(record.transactionId())) {
            ids.add(record.transactionId());
        }
    }

    // --- transactions, property escrow, cell journal and quarantine (0.6.0) ---

    /** The receipt for {@code transactionId}, or null when this transfer has never been attempted. */
    @Nullable
    public TransactionReceipt transaction(UUID transactionId) {
        return transactionId == null ? null : transactions.get(transactionId);
    }

    /**
     * Inserts or replaces one receipt.
     *
     * <p>Replacing is the normal case rather than the exception: a transfer writes itself twice, once
     * when the source is debited and once when the outcome is known, and the second write is the whole
     * point of the first.
     */
    public CapacityResult putTransaction(TransactionReceipt receipt) {
        if (receipt == null || receipt.id() == null || frozen()) {
            return CapacityResult.FULL;
        }
        if (atCapacity(transactions, receipt.id(), MAX_TRANSACTIONS, "transaction receipt")) {
            return CapacityResult.FULL;
        }
        transactions.put(receipt.id(), receipt);
        setDirty();
        return CapacityResult.OK;
    }

    /** Every receipt, oldest first. A copy: pruning iterates it while removing from it. */
    public List<TransactionReceipt> transactions() {
        return new ArrayList<>(transactions.values());
    }

    /**
     * Forgets finished receipts older than the retention window.
     *
     * <p>Only {@link TransactionReceipt.State#terminal()} receipts are eligible. A
     * {@code NEEDS_RECONCILIATION} row is money this mod took and did not deliver, and pruning it
     * would turn a repairable debt into a silent theft however old it got.
     *
     * @return how many were dropped
     */
    public int pruneTransactions(long gameTime) {
        if (frozen()) {
            return 0;
        }
        long horizon = TRANSACTION_RETENTION_DAYS * TICKS_PER_DAY;
        int before = transactions.size();
        transactions.values().removeIf(receipt ->
                receipt.state().terminal() && gameTime - receipt.stamp() >= horizon);
        int removed = before - transactions.size();
        if (removed > 0) {
            setDirty();
        }
        return removed;
    }

    /** Everything still owed to {@code owner}, oldest first. Never null. */
    public List<PropertyLot> propertyEscrowFor(UUID owner) {
        List<PropertyLot> out = new ArrayList<>();
        if (owner == null) {
            return out;
        }
        for (PropertyLot lot : propertyEscrow.values()) {
            if (owner.equals(lot.owner())) {
                out.add(lot);
            }
        }
        return out;
    }

    /** Every lot in the world. A copy: delivery iterates it while removing from it. */
    public List<PropertyLot> propertyEscrow() {
        return new ArrayList<>(propertyEscrow.values());
    }

    /** Inserts or replaces one lot. A full escrow refuses, so the caller keeps the ledger row instead. */
    public CapacityResult putPropertyLot(PropertyLot lot) {
        if (lot == null || lot.lotId() == null || frozen()) {
            return CapacityResult.FULL;
        }
        if (atCapacity(propertyEscrow, lot.lotId(), MAX_PROPERTY_ESCROW, "property escrow")) {
            return CapacityResult.FULL;
        }
        propertyEscrow.put(lot.lotId(), lot);
        setDirty();
        return CapacityResult.OK;
    }

    /** Forgets one lot. Only a completed delivery does this. */
    public boolean removePropertyLot(UUID lotId) {
        if (lotId == null || frozen()) {
            return false;
        }
        if (propertyEscrow.remove(lotId) != null) {
            setDirty();
            return true;
        }
        return false;
    }

    /** Cells with blocks still standing, in the order they failed. A copy: the retry removes from it. */
    public List<HoldingCell> pendingCellRestorations() {
        return new ArrayList<>(pendingCellRestorations.values());
    }

    /** The unfinished restoration for {@code prisoner}, or null when nothing is outstanding. */
    @Nullable
    public HoldingCell pendingCellRestoration(UUID prisoner) {
        return prisoner == null ? null : pendingCellRestorations.get(prisoner);
    }

    /**
     * Records that part of a cell could not be put back.
     *
     * <p>Not capacity-checked against a ceiling of its own: an entry here is always the remains of a
     * cell that was already in {@code holdingCells}, so the roster cap has already bounded it.
     */
    public void putPendingCellRestoration(HoldingCell remains) {
        if (remains == null || remains.prisoner() == null || frozen()) {
            return;
        }
        pendingCellRestorations.put(remains.prisoner(), remains);
        setDirty();
    }

    public void removePendingCellRestoration(UUID prisoner) {
        if (prisoner == null || frozen()) {
            return;
        }
        if (pendingCellRestorations.remove(prisoner) != null) {
            setDirty();
        }
    }

    /**
     * Sets a row aside instead of discarding it.
     *
     * <p>Called from the load path, before {@code frozen()} could mean anything, so it deliberately
     * does not consult it. Oldest-first eviction past the ceiling: the first bad rows in a file are
     * the ones that say most about how it went wrong.
     */
    public void quarantine(String reason, @Nullable CompoundTag original) {
        CompoundTag entry = new CompoundTag();
        entry.putString("reason", reason == null ? "unknown" : reason);
        if (original != null) {
            entry.put("tag", original.copy());
        }
        quarantine.add(entry);
        while (quarantine.size() > MAX_QUARANTINE) {
            quarantine.remove(0);
            quarantineDropped++;
        }
    }

    /** Every quarantined row, each a {@code {reason, tag}} compound. A copy. */
    public List<CompoundTag> quarantined() {
        List<CompoundTag> out = new ArrayList<>(quarantine.size());
        quarantine.forEach(entry -> out.add(entry.copy()));
        return out;
    }

    public int quarantineCount() {
        return quarantine.size();
    }

    // --- persistence ---

    @Override
    public CompoundTag save(CompoundTag tag) {
        // A store from a newer jar was never parsed; hand it back exactly as found rather than
        // overwriting it with what this build happens to understand.
        if (frozen() && reserved.contains(FUTURE_KEY, Tag.TAG_COMPOUND)) {
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

        ListTag cellList = new ListTag();
        for (HoldingCell cell : holdingCells.values()) {
            cellList.add(cell.save());
        }
        tag.put("holdingCells", cellList);

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

        ListTag observationList = new ListTag();
        observations.values().forEach(observation -> observationList.add(observation.save()));
        tag.put("observations", observationList);
        ListTag reportList = new ListTag();
        reports.values().forEach(report -> reportList.add(report.save()));
        tag.put("reports", reportList);

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

        ListTag criminalList = new ListTag();
        criminalVillagers.values().forEach(record -> criminalList.add(record.save()));
        tag.put("criminalVillagers", criminalList);
        ListTag stolenList = new ListTag();
        stolenGoods.values().forEach(record -> stolenList.add(record.save()));
        tag.put("stolenGoods", stolenList);
        ListTag warrantList = new ListTag();
        warrants.values().forEach(warrant -> warrantList.add(warrant.save()));
        tag.put("warrants", warrantList);
        ListTag claimList = new ListTag();
        bountyClaims.forEach((key, record) -> {
            CompoundTag entry = record.save();
            entry.putString("claimKey", key);
            claimList.add(entry);
        });
        tag.put("bountyClaims", claimList);
        ListTag contractList = new ListTag();
        bountyContracts.values().forEach(contract -> contractList.add(contract.save()));
        tag.put("bountyContracts", contractList);
        CompoundTag restockTag = new CompoundTag();
        fenceRestockDay.forEach((fence, day) -> restockTag.putLong(fence.toString(), day));
        tag.put("fenceRestockDay", restockTag);
        ListTag stockList = new ListTag();
        fenceStock.values().forEach(record -> stockList.add(record.save()));
        tag.put("fenceStock", stockList);

        // 0.6.0 collections. Each is absent rather than empty in a world that has never needed one.
        ListTag transactionList = new ListTag();
        transactions.values().forEach(receipt -> transactionList.add(receipt.save()));
        tag.put("transactions", transactionList);
        ListTag escrowList = new ListTag();
        propertyEscrow.values().forEach(lot -> escrowList.add(lot.save()));
        tag.put("propertyEscrow", escrowList);
        ListTag pendingCellList = new ListTag();
        pendingCellRestorations.values().forEach(cell -> pendingCellList.add(cell.save()));
        tag.put("pendingCellRestorations", pendingCellList);
        // Written back exactly as it was read. A row nobody can parse is still a row somebody's
        // history is in, and the one thing worse than not loading it is losing it.
        ListTag quarantineList = new ListTag();
        quarantine.forEach(entry -> quarantineList.add(entry.copy()));
        tag.put("quarantine", quarantineList);

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

        try {
            readInto(data, CrimeDataMigrations.migrate(raw));
        } catch (Throwable t) {
            // Not the per-row skip below: this is the migration or the outer shape failing, which
            // means what is in memory bears no relation to what is on disk. Park the file verbatim and
            // run the session read-only rather than saving a partial reading over the real thing.
            data.loadFailed = true;
            data.reserved.put(FUTURE_KEY, raw.copy());
            McaCrime.LOGGER.error("MCA: Crime could not read its world data. The file has been left "
                    + "untouched and no crime state will be written this session.", t);
        }
        return data;
    }

    /** Everything that can be read row by row. Throwing here fails the whole load; skipping does not. */
    private static void readInto(CrimeWorldData data, CompoundTag tag) {
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
                // Set aside rather than dropped: a case nobody can parse is still a case.
                data.quarantine("ledger: " + e.getMessage(), ledgerList.getCompound(i));
                continue;
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

        ListTag cellList = tag.getList("holdingCells", Tag.TAG_COMPOUND);
        for (int i = 0; i < cellList.size(); i++) {
            HoldingCell cell = HoldingCell.load(cellList.getCompound(i));
            if (cell != null) {
                data.holdingCells.put(cell.prisoner(), cell);
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
                    data.quarantine("custody: " + e.getMessage(), custodyTag.getCompound(key));
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
                data.quarantine("ransom: " + e.getMessage(), ransomTag.getCompound(key));
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
            } catch (RuntimeException e) {
                data.quarantine("villager profile: " + e.getMessage(), profileTag.getCompound(key));
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

        ListTag observationList = tag.getList("observations", Tag.TAG_COMPOUND);
        for (int i = 0; i < observationList.size(); i++) {
            try {
                CrimeObservation observation = CrimeObservation.load(observationList.getCompound(i));
                if (!data.observations.containsKey(observation.observationId())) {
                    data.observations.put(observation.observationId(), observation);
                    data.observationsByObserver
                            .computeIfAbsent(observation.observerId(), k -> new ArrayList<>())
                            .add(observation.observationId());
                }
            } catch (RuntimeException e) {
                data.quarantine("observation: " + e.getMessage(), observationList.getCompound(i));
            }
        }
        ListTag reportList = tag.getList("reports", Tag.TAG_COMPOUND);
        for (int i = 0; i < reportList.size(); i++) {
            try {
                CrimeReport report = CrimeReport.load(reportList.getCompound(i));
                if (!data.reports.containsKey(report.reportId())) {
                    data.reports.put(report.reportId(), report);
                    data.reportsBySuspect.computeIfAbsent(report.suspectId(), k -> new ArrayList<>())
                            .add(report.reportId());
                }
            } catch (RuntimeException e) {
                data.quarantine("report: " + e.getMessage(), reportList.getCompound(i));
            }
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
                    data.quarantine("dedupe: " + e.getMessage(), perPlayer.getCompound(key));
                }
            }
            if (!entries.isEmpty()) {
                data.dedupe.put(player, entries);
            }
        }

        // 0.5.1 collections. Every one of them defaults to empty when absent -- a schema-6 world simply
        // has no criminals, no warrants and no stolen goods, which is the truthful answer rather than
        // something to synthesise. Each is capped, and each reports at most one line however many
        // entries are bad: a corrupt file must not be able to write thousands of lines into a log.
        ListTag criminalList = tag.getList("criminalVillagers", Tag.TAG_COMPOUND);
        for (int i = 0; i < criminalList.size(); i++) {
            try {
                CriminalVillagerRecord record = CriminalVillagerRecord.load(criminalList.getCompound(i));
                data.criminalVillagers.put(record.villager(), record);
            } catch (RuntimeException e) {
                data.quarantine("criminal villager: " + e.getMessage(), criminalList.getCompound(i));
            }
        }
        warnOverflow("criminal villager", data.criminalVillagers.size(), MAX_CRIMINAL_VILLAGERS);

        ListTag stolenList = tag.getList("stolenGoods", Tag.TAG_COMPOUND);
        for (int i = 0; i < stolenList.size(); i++) {
            try {
                StolenGoodsRecord record = StolenGoodsRecord.load(stolenList.getCompound(i));
                data.stolenGoods.put(record.transactionId(), record);
                data.indexStolen(record);
            } catch (RuntimeException e) {
                data.quarantine("stolen goods: " + e.getMessage(), stolenList.getCompound(i));
            }
        }
        warnOverflow("stolen goods", data.stolenGoods.size(), MAX_STOLEN_GOODS);

        ListTag warrantList = tag.getList("warrants", Tag.TAG_COMPOUND);
        for (int i = 0; i < warrantList.size(); i++) {
            try {
                Warrant warrant = Warrant.load(warrantList.getCompound(i));
                data.warrants.put(warrant.offender(), warrant);
            } catch (RuntimeException e) {
                data.quarantine("warrant: " + e.getMessage(), warrantList.getCompound(i));
            }
        }
        warnOverflow("warrant", data.warrants.size(), MAX_WARRANTS);

        ListTag claimList = tag.getList("bountyClaims", Tag.TAG_COMPOUND);
        for (int i = 0; i < claimList.size(); i++) {
            try {
                CompoundTag entry = claimList.getCompound(i);
                BountyClaimRecord record = BountyClaimRecord.load(entry);
                String key = entry.contains("claimKey")
                        ? entry.getString("claimKey")
                        : record.target() + "/" + record.warrantId() + "/" + record.revision();
                data.bountyClaims.put(key, record);
            } catch (RuntimeException e) {
                data.quarantine("bounty claim: " + e.getMessage(), claimList.getCompound(i));
            }
        }
        warnOverflow("bounty claim", data.bountyClaims.size(), MAX_BOUNTY_CLAIMS);

        ListTag contractList = tag.getList("bountyContracts", Tag.TAG_COMPOUND);
        for (int i = 0; i < contractList.size(); i++) {
            try {
                BountyContractRecord contract = BountyContractRecord.load(contractList.getCompound(i));
                data.bountyContracts.put(contract.contractId(), contract);
            } catch (RuntimeException e) {
                data.quarantine("bounty contract: " + e.getMessage(), contractList.getCompound(i));
            }
        }
        warnOverflow("bounty contract", data.bountyContracts.size(), MAX_BOUNTY_CONTRACTS);

        CompoundTag restockTag = tag.getCompound("fenceRestockDay");
        int badRestocks = 0;
        for (String key : restockTag.getAllKeys()) {
            try {
                data.fenceRestockDay.put(UUID.fromString(key), Math.max(0L, restockTag.getLong(key)));
            } catch (IllegalArgumentException e) {
                badRestocks++; // a bare key with no compound behind it; there is nothing to set aside
            }
        }
        warnSkipped("fence restock stamp", badRestocks);
        warnOverflow("fence restock stamp", data.fenceRestockDay.size(), MAX_FENCE_RESTOCK_DAYS);

        ListTag stockList = tag.getList("fenceStock", Tag.TAG_COMPOUND);
        for (int i = 0; i < stockList.size(); i++) {
            try {
                FenceStockRecord record = FenceStockRecord.load(stockList.getCompound(i));
                data.fenceStock.put(record.fence(), record);
            } catch (RuntimeException e) {
                data.quarantine("fence stock: " + e.getMessage(), stockList.getCompound(i));
            }
        }
        warnOverflow("fence stock", data.fenceStock.size(), MAX_FENCE_STOCK);

        // 0.6.0 collections. Absent reads as empty on every world written before this release.
        ListTag transactionList = tag.getList("transactions", Tag.TAG_COMPOUND);
        for (int i = 0; i < transactionList.size(); i++) {
            try {
                TransactionReceipt receipt = TransactionReceipt.load(transactionList.getCompound(i));
                data.transactions.put(receipt.id(), receipt);
            } catch (RuntimeException e) {
                data.quarantine("transaction receipt: " + e.getMessage(), transactionList.getCompound(i));
            }
        }
        warnOverflow("transaction receipt", data.transactions.size(), MAX_TRANSACTIONS);

        ListTag escrowList = tag.getList("propertyEscrow", Tag.TAG_COMPOUND);
        for (int i = 0; i < escrowList.size(); i++) {
            try {
                PropertyLot lot = PropertyLot.load(escrowList.getCompound(i));
                data.propertyEscrow.put(lot.lotId(), lot);
            } catch (RuntimeException e) {
                data.quarantine("property lot: " + e.getMessage(), escrowList.getCompound(i));
            }
        }
        warnOverflow("property escrow", data.propertyEscrow.size(), MAX_PROPERTY_ESCROW);

        ListTag pendingCellList = tag.getList("pendingCellRestorations", Tag.TAG_COMPOUND);
        for (int i = 0; i < pendingCellList.size(); i++) {
            HoldingCell remains = HoldingCell.load(pendingCellList.getCompound(i));
            if (remains != null) {
                data.pendingCellRestorations.put(remains.prisoner(), remains);
            } else {
                data.quarantine("pending cell restoration", pendingCellList.getCompound(i));
            }
        }

        // Read straight back in, unexamined. Parsing a quarantined row is exactly what failed.
        ListTag quarantineList = tag.getList("quarantine", Tag.TAG_COMPOUND);
        for (int i = 0; i < quarantineList.size() && data.quarantine.size() < MAX_QUARANTINE; i++) {
            data.quarantine.add(quarantineList.getCompound(i).copy());
        }

        // Capture reserved later-phase slots verbatim for forward compatibility.
        for (String key : RESERVED_KEYS) {
            if (tag.contains(key)) {
                data.reserved.put(key, tag.get(key).copy());
            }
        }
        if (data.quarantineDropped > 0) {
            McaCrime.LOGGER.warn("MCA: Crime quarantined the first {} unreadable row(s) and dropped {} "
                    + "further one(s); the file is systematically damaged.", MAX_QUARANTINE,
                    data.quarantineDropped);
        }
    }

    /**
     * One line when a collection came back over its ceiling. Nothing is dropped: the load loops used to
     * stop at the cap, which meant an oversized file silently lost its tail every time it was opened.
     * Everything is read, and the insertion cap is what stops it growing any further.
     */
    private static void warnOverflow(String noun, int loaded, int max) {
        if (loaded > max) {
            McaCrime.LOGGER.warn("MCA: Crime loaded {} {} record(s), over the {} ceiling. All of them were "
                    + "kept, but no further one can be added until the table shrinks.", loaded, noun, max);
        }
    }

    /** One aggregate line per collection, however many entries were bad. Silent when none were. */
    private static void warnSkipped(String noun, int skipped) {
        if (skipped > 0) {
            McaCrime.LOGGER.warn("MCA: Crime skipped {} malformed {} entr(ies) while loading; the rest of the "
                    + "store was read normally.", skipped, noun);
        }
    }

    /**
     * A stable replacement id for a duplicated record. Derived from the original plus its ordinal, so
     * loading the same file twice always produces the same repair.
     */
    private static UUID deriveRepairId(UUID original, int ordinal) {
        return UUID.nameUUIDFromBytes((original + ":dup:" + ordinal).getBytes(StandardCharsets.UTF_8));
    }
}
