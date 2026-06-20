package dev.otectus.mcacrime.state;

import dev.otectus.mcacrime.crime.Band;
import dev.otectus.mcacrime.jail.JailState;
import net.minecraft.nbt.CompoundTag;

import javax.annotation.Nullable;
import java.util.UUID;

/**
 * A player's own crime state (spec §2.1): Karma, Heat, the cached band, the online-tick decay clock,
 * and the daily anti-farm counters. Held in a Forge capability and serialised to the player's NBT, so
 * it survives logout, death (copied on {@code PlayerEvent.Clone}), dimension change, and restart
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
public final class PlayerCrimeData {

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

    public void setLastSurrenderTick(long lastSurrenderTick) {
        this.lastSurrenderTick = lastSurrenderTick;
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
        this.dailyKarmaCounters.copyFrom(other.dailyKarmaCounters);
        this.heldCaptiveRef = other.heldCaptiveRef;
        this.heldByRef = other.heldByRef;
        this.jail = other.jail == null ? null : other.jail.copy(); // death does NOT clear jail (§7.1)
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
    }

    private static Band parseBand(String name) {
        try {
            return Band.valueOf(name);
        } catch (IllegalArgumentException e) {
            return Band.GREY;
        }
    }
}
