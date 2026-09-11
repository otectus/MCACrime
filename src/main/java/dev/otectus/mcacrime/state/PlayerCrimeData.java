package dev.otectus.mcacrime.state;

import dev.otectus.mcacrime.crime.Band;
import dev.otectus.mcacrime.enforcement.ArrestPhase;
import dev.otectus.mcacrime.enforcement.ArrestState;
import dev.otectus.mcacrime.jail.JailState;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.neoforged.neoforge.common.util.INBTSerializable;

import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

import org.jetbrains.annotations.Nullable;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * A player's own crime state (spec §2.1): Karma, Heat, the cached band, the online-tick decay clock,
 * and the daily anti-farm counters. Held in a Forge capability and serialised to the player's NBT, so
 * it survives logout, death (copied by {@code copyOnDeath}), dimension change, and restart
 * (spec §7.1, §18). Server-authoritative — never trust a client copy.
 *
 * <p>Pure data + NBT (no Forge config / server deps) so it round-trips in unit tests. All mutation in
 * the running mod must go through {@link dev.otectus.mcacrime.engine.CrimeState}; the setters here are
 * the low-level write path that chokepoint uses.
 *
 * <p>Reserved fields ({@link #heldCaptiveRef}, {@link #heldByRef}) are present per §2.1 but unused in
 * 0.1.0; the jail record is deferred to Phase 3 (its type does not exist yet) and {@link #load} tolerates
 * its later addition with zero migration.
 */
public final class PlayerCrimeData implements INBTSerializable<CompoundTag> {

    private long karma;
    private long heat;
    private Band cachedBand = Band.GREY;
    private boolean wantedCached;

    /** Mod-owned monotonic counter: +1 per server player tick. The ONLY clock decay reads (spec §7.1). */
    private long onlineTicksLived;
    /** Online-tick anchor for the ±1 / MC-day karma normalisation. */
    private long lastKarmaDecayTick;
    /** Online-tick anchor for the per-online-minute heat bleed-off. */
    private long lastHeatDecayTick;
    /** Online-tick of the player's last {@code /crime surrender}, a transient capture vulnerability (§8.2). */
    private long lastSurrenderTick;

    /**
     * The online tick at which a refusal to answer a guard stops counting as resisting arrest, or 0
     * when the player is not resisting.
     *
     * <p>This used to be a static map in {@code GuardChallengeService} that was purged every scan for
     * anybody who was not already a Legal Target — which is to say, for exactly the players whose
     * refusal was the only thing making force lawful. The refusal was erased before it could be acted
     * on and a fresh challenge opened in its place, so refusing a guard produced an endless loop of
     * challenges and no consequence at all. Refusing a lawful challenge is itself an offence, so it
     * belongs in the player's persisted state where it can outlive a scan, a relog, and a restart.
     *
     * <p>Measured in online ticks, like the sentence clock, so logging out does not run it down.
     */
    private long resistingArrestUntilTick;

    /**
     * How many warrants this player has had closed against them (0.5.1). The repeat-offender term in
     * the bounty price.
     *
     * <p>Bumped once per warrant, on close, by {@code WarrantService} — never on open, or a player who
     * is Wanted right now would be priced as though they had already served for it. Absent in every
     * pre-0.5.1 save, where {@code getInt} yields 0 and reads correctly as "no history". No migration.
     */
    private int priorWarrants;

    /**
     * The tick, on this player's own online clock, until which no villager thief may rob them (0.7.0).
     *
     * <p>Every existing mugging cooldown is scoped to the <em>thief</em>, which is why one player could
     * be robbed by four thieves in as many minutes and each of them was inside its own limit. This is
     * the counterpart nobody had written: a cooldown that belongs to the victim and is therefore shared
     * by every thief in the world. Grants only ever take the maximum, so a shorter one arriving during a
     * longer one never shortens it.
     *
     * <p>Absent in every pre-0.7.0 save, where {@code getLong} yields 0 and reads correctly as "not
     * protected". No migration.
     */
    private long mugProtectionUntilTick;

    /**
     * Thief id -> the tick this particular pair may meet again (0.7.0). Bounded at
     * {@link #MAX_RECENT_MUGGERS}: the oldest expiry is evicted when a ninth thief is recorded, because
     * this is a fairness memory and not an audit log, and an unbounded map on a player capability is a
     * save file that grows for as long as the world does.
     */
    private final Map<UUID, Long> recentMuggers = new LinkedHashMap<>();

    /** Muggings committed against this player today, against {@link #muggingDay}. */
    private int muggingsToday;
    /** The world day {@link #muggingsToday} counts, so the cap rolls over rather than being cleared. */
    private long muggingDay;

    /**
     * The last contraband haul this player was charged for, and when (0.7.0).
     *
     * <p>The fingerprint is what stops a second guard, or the same guard a tick later, charging a
     * player again for the same items; the tick is what lets the charge come back once the recharge
     * window has passed. Zero is "never charged", which no real fingerprint ever equals.
     */
    private long lastContrabandFingerprint;
    private long lastContrabandChargeTick;

    /** How many thieves a player's pair-cooldown memory holds before the oldest is forgotten. */
    public static final int MAX_RECENT_MUGGERS = 8;

    private final DailyKarmaCounters dailyKarmaCounters = new DailyKarmaCounters();

    /** Reserved (Phase 4): the captive this player is currently holding. */
    @Nullable
    private UUID heldCaptiveRef;
    /** Reserved (Phase 4): the captor currently holding this player. */
    @Nullable
    private UUID heldByRef;

    /** Active jail sentence (spec §2.1, §7), or null if not jailed. Copied on death so jail survives (§7.1). */
    @Nullable
    private JailState jail;

    /**
     * The in-flight arrest, or null when none. The one authoritative answer to "is this player being
     * arrested right now", replacing a fact that used to be inferred from whichever of five stores a
     * caller happened to consult.
     *
     * <p>Copied on death for the same reason the jail record is: being killed mid-escort is not a way
     * out of an arrest.
     */
    @Nullable
    private ArrestState arrest;

    public long getKarma() {
        return karma;
    }

    public void setKarma(long karma) {
        this.karma = karma;
    }

    public long getHeat() {
        return heat;
    }

    public void setHeat(long heat) {
        this.heat = heat;
    }

    public Band getCachedBand() {
        return cachedBand;
    }

    public void setCachedBand(Band cachedBand) {
        this.cachedBand = cachedBand;
    }

    public boolean isWantedCached() {
        return wantedCached;
    }

    public void setWantedCached(boolean wantedCached) {
        this.wantedCached = wantedCached;
    }

    public long getOnlineTicksLived() {
        return onlineTicksLived;
    }

    public void setOnlineTicksLived(long onlineTicksLived) {
        this.onlineTicksLived = onlineTicksLived;
    }

    /** Advances the online-tick clock by one and returns the new value. */
    public long incrementOnlineTicks() {
        return ++onlineTicksLived;
    }

    public long getLastKarmaDecayTick() {
        return lastKarmaDecayTick;
    }

    public void setLastKarmaDecayTick(long lastKarmaDecayTick) {
        this.lastKarmaDecayTick = lastKarmaDecayTick;
    }

    public long getLastHeatDecayTick() {
        return lastHeatDecayTick;
    }

    public void setLastHeatDecayTick(long lastHeatDecayTick) {
        this.lastHeatDecayTick = lastHeatDecayTick;
    }

    public long getLastSurrenderTick() {
        return lastSurrenderTick;
    }

    public long getResistingArrestUntilTick() {
        return resistingArrestUntilTick;
    }

    public void setResistingArrestUntilTick(long resistingArrestUntilTick) {
        this.resistingArrestUntilTick = Math.max(0L, resistingArrestUntilTick);
    }

    /** True while a refusal is still standing, by this player's own online clock. */
    public boolean isResistingArrest() {
        return resistingArrestUntilTick > onlineTicksLived;
    }

    public void setLastSurrenderTick(long lastSurrenderTick) {
        this.lastSurrenderTick = lastSurrenderTick;
    }

    public int getPriorWarrants() {
        return priorWarrants;
    }

    public void setPriorWarrants(int priorWarrants) {
        this.priorWarrants = Math.max(0, priorWarrants);
    }

    /** One more closed warrant on the record. */
    public void incrementPriorWarrants() {
        priorWarrants++;
    }

    public long getMugProtectionUntilTick() {
        return mugProtectionUntilTick;
    }

    public void setMugProtectionUntilTick(long mugProtectionUntilTick) {
        this.mugProtectionUntilTick = Math.max(0L, mugProtectionUntilTick);
    }

    /** The live map, so a caller that has the world clock can prune and record against it. */
    public Map<UUID, Long> recentMuggers() {
        return recentMuggers;
    }

    /** The tick this thief may rob this player again, or 0 when they never have. */
    public long pairCooldownUntil(@Nullable UUID thief) {
        Long until = thief == null ? null : recentMuggers.get(thief);
        return until == null ? 0L : until;
    }

    /**
     * Remembers that this thief robbed this player, evicting the oldest entry when the bound is
     * reached. Expired entries are dropped first, so eviction only ever happens to a player being
     * worked over by nine live thieves at once.
     */
    public void recordMugger(UUID thief, long untilTick, long now) {
        if (thief == null) {
            return;
        }
        pruneRecentMuggers(now);
        Long existing = recentMuggers.get(thief);
        recentMuggers.put(thief, existing == null ? untilTick : Math.max(existing, untilTick));
        while (recentMuggers.size() > MAX_RECENT_MUGGERS) {
            Iterator<UUID> oldest = recentMuggers.keySet().iterator();
            oldest.next();
            oldest.remove();
        }
    }

    /** Drops pair cooldowns that have run out. Cheap, bounded, and idempotent. */
    public void pruneRecentMuggers(long now) {
        recentMuggers.entrySet().removeIf(entry -> entry.getValue() == null || entry.getValue() <= now);
    }

    public int getMuggingsToday() {
        return muggingsToday;
    }

    public long getMuggingDay() {
        return muggingDay;
    }

    /** Rolls the counter over when the day has changed, then returns the count for today. */
    public int muggingsOn(long day) {
        if (muggingDay != day) {
            muggingDay = day;
            muggingsToday = 0;
        }
        return muggingsToday;
    }

    /** One more mugging suffered today, rolling the day over first. */
    public void recordMuggingOn(long day) {
        muggingsOn(day);
        muggingsToday++;
    }

    public void setMuggingsToday(int muggingsToday) {
        this.muggingsToday = Math.max(0, muggingsToday);
    }

    public void setMuggingDay(long muggingDay) {
        this.muggingDay = muggingDay;
    }

    public long getLastContrabandFingerprint() {
        return lastContrabandFingerprint;
    }

    public void setLastContrabandFingerprint(long lastContrabandFingerprint) {
        this.lastContrabandFingerprint = lastContrabandFingerprint;
    }

    public long getLastContrabandChargeTick() {
        return lastContrabandChargeTick;
    }

    public void setLastContrabandChargeTick(long lastContrabandChargeTick) {
        this.lastContrabandChargeTick = lastContrabandChargeTick;
    }

    public DailyKarmaCounters dailyKarmaCounters() {
        return dailyKarmaCounters;
    }

    @Nullable
    public UUID getHeldCaptiveRef() {
        return heldCaptiveRef;
    }

    public void setHeldCaptiveRef(@Nullable UUID heldCaptiveRef) {
        this.heldCaptiveRef = heldCaptiveRef;
    }

    @Nullable
    public UUID getHeldByRef() {
        return heldByRef;
    }

    public void setHeldByRef(@Nullable UUID heldByRef) {
        this.heldByRef = heldByRef;
    }

    @Nullable
    public JailState getJail() {
        return jail;
    }

    public void setJail(@Nullable JailState jail) {
        this.jail = jail;
    }

    @Nullable
    public ArrestState getArrest() {
        return arrest;
    }

    public void setArrest(@Nullable ArrestState arrest) {
        this.arrest = arrest;
    }

    /** The arrest phase, never null. {@code NONE} when there is no arrest, so callers never null-check. */
    public ArrestPhase arrestPhase() {
        return arrest == null ? ArrestPhase.NONE : arrest.getPhase();
    }

    public boolean isJailed() {
        return jail != null;
    }

    public void copyFrom(PlayerCrimeData other) {
        this.karma = other.karma;
        this.heat = other.heat;
        this.cachedBand = other.cachedBand;
        this.wantedCached = other.wantedCached;
        this.onlineTicksLived = other.onlineTicksLived;
        this.lastKarmaDecayTick = other.lastKarmaDecayTick;
        this.lastHeatDecayTick = other.lastHeatDecayTick;
        this.lastSurrenderTick = other.lastSurrenderTick;
        this.resistingArrestUntilTick = other.resistingArrestUntilTick;
        this.priorWarrants = other.priorWarrants;
        // Mugging protection is copied on death for the same reason the jail record is: dying is one of
        // the moments a grant is made, and losing the grant on the way through would hand the thief that
        // just killed you a fresh victim.
        this.mugProtectionUntilTick = other.mugProtectionUntilTick;
        this.recentMuggers.clear();
        this.recentMuggers.putAll(other.recentMuggers);
        this.muggingsToday = other.muggingsToday;
        this.muggingDay = other.muggingDay;
        this.lastContrabandFingerprint = other.lastContrabandFingerprint;
        this.lastContrabandChargeTick = other.lastContrabandChargeTick;
        this.dailyKarmaCounters.copyFrom(other.dailyKarmaCounters);
        this.heldCaptiveRef = other.heldCaptiveRef;
        this.heldByRef = other.heldByRef;
        this.jail = other.jail == null ? null : other.jail.copy(); // death does NOT clear jail (§7.1)
        this.arrest = other.arrest == null ? null : other.arrest.copy(); // nor does it end an arrest
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putLong("karma", karma);
        tag.putLong("heat", heat);
        tag.putString("band", cachedBand.name());
        tag.putBoolean("wanted", wantedCached);
        tag.putLong("onlineTicksLived", onlineTicksLived);
        tag.putLong("lastKarmaDecayTick", lastKarmaDecayTick);
        tag.putLong("lastHeatDecayTick", lastHeatDecayTick);
        tag.putLong("lastSurrenderTick", lastSurrenderTick);
        tag.putLong("resistingArrestUntilTick", resistingArrestUntilTick);
        tag.putInt("priorWarrants", priorWarrants);
        tag.putLong("mugProtectionUntilTick", mugProtectionUntilTick);
        tag.putInt("muggingsToday", muggingsToday);
        tag.putLong("muggingDay", muggingDay);
        tag.putLong("lastContrabandFingerprint", lastContrabandFingerprint);
        tag.putLong("lastContrabandChargeTick", lastContrabandChargeTick);
        if (!recentMuggers.isEmpty()) {
            ListTag muggers = new ListTag();
            recentMuggers.forEach((thief, until) -> {
                CompoundTag entry = new CompoundTag();
                entry.putUUID("thief", thief);
                entry.putLong("until", until == null ? 0L : until);
                muggers.add(entry);
            });
            tag.put("recentMuggers", muggers);
        }
        tag.put("dailyKarma", dailyKarmaCounters.save());
        if (heldCaptiveRef != null) {
            tag.putUUID("heldCaptiveRef", heldCaptiveRef);
        }
        if (heldByRef != null) {
            tag.putUUID("heldByRef", heldByRef);
        }
        if (jail != null) {
            tag.put("jail", jail.save());
        }
        if (arrest != null) {
            tag.put("arrest", arrest.save());
        }
        return tag;
    }

    public void load(CompoundTag tag) {
        karma = tag.getLong("karma");
        heat = tag.getLong("heat");
        wantedCached = tag.getBoolean("wanted");
        onlineTicksLived = tag.getLong("onlineTicksLived");
        lastKarmaDecayTick = tag.getLong("lastKarmaDecayTick");
        lastHeatDecayTick = tag.getLong("lastHeatDecayTick");
        lastSurrenderTick = tag.getLong("lastSurrenderTick");
        // Absent in pre-0.4.0 saves; getLong yields 0, which reads as "not resisting". No migration needed.
        resistingArrestUntilTick = tag.getLong("resistingArrestUntilTick");
        // Absent in pre-0.5.1 saves; getInt yields 0, which reads as a clean record. No migration.
        priorWarrants = Math.max(0, tag.getInt("priorWarrants"));
        // All absent in every pre-0.7.0 save, where 0 reads as "never protected, never robbed, never
        // searched". No migration, like the resisting flag and the warrant count before them.
        mugProtectionUntilTick = Math.max(0L, tag.getLong("mugProtectionUntilTick"));
        muggingsToday = Math.max(0, tag.getInt("muggingsToday"));
        muggingDay = tag.getLong("muggingDay");
        lastContrabandFingerprint = tag.getLong("lastContrabandFingerprint");
        lastContrabandChargeTick = tag.getLong("lastContrabandChargeTick");
        recentMuggers.clear();
        ListTag muggers = tag.getList("recentMuggers", Tag.TAG_COMPOUND);
        for (int i = 0; i < muggers.size() && recentMuggers.size() < MAX_RECENT_MUGGERS; i++) {
            CompoundTag entry = muggers.getCompound(i);
            // A non-positive expiry is one that has already run out or was never written properly; the
            // world clock is not available here, so live expiry is pruned by the first caller that has it.
            if (entry.hasUUID("thief") && entry.getLong("until") > 0L) {
                recentMuggers.put(entry.getUUID("thief"), entry.getLong("until"));
            }
        }
        dailyKarmaCounters.load(tag.getCompound("dailyKarma"));
        // Band is stored, but derive it from karma when the key is absent (old saves / hand-edits).
        if (tag.contains("band")) {
            cachedBand = parseBand(tag.getString("band"));
        } else {
            cachedBand = Band.fromKarma(karma);
        }
        heldCaptiveRef = tag.hasUUID("heldCaptiveRef") ? tag.getUUID("heldCaptiveRef") : null;
        heldByRef = tag.hasUUID("heldByRef") ? tag.getUUID("heldByRef") : null;
        jail = tag.contains("jail") ? JailState.load(tag.getCompound("jail")) : null;
        // Absent in every pre-0.4.0 save, which reads as no arrest. No migration, like the resisting
        // flag before it.
        arrest = tag.contains("arrest") ? ArrestState.load(tag.getCompound("arrest")) : null;
    }

    /**
     * The attachment persistence hook (spec §7.1). Delegates to {@link #save()} so the on-disk keys
     * stay identical to the 1.20.1 capability blob; nothing here is registry-aware, so the provider
     * is unused.
     */
    @Override
    public CompoundTag serializeNBT(HolderLookup.Provider provider) {
        return save();
    }

    @Override
    public void deserializeNBT(HolderLookup.Provider provider, CompoundTag tag) {
        load(tag);
    }

    private static Band parseBand(String name) {
        try {
            return Band.valueOf(name);
        } catch (IllegalArgumentException e) {
            return Band.GREY;
        }
    }
}
