package dev.otectus.mcacrime.captivity;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;

import org.jetbrains.annotations.Nullable;
import java.util.UUID;

/**
 * A single captivity (spec §2.3): who is held, by whom, lawfully or not, with what restraint, where, and
 * for how long. Lives in the world custody table ({@link dev.otectus.mcacrime.state.world.CrimeWorldData})
 * keyed by {@link #captive}, so it is authoritative across players and survives logout/death/restart.
 *
 * <p>The structural twin of {@link dev.otectus.mcacrime.jail.JailState}, generalised to also hold NPCs and
 * to record an {@link CustodyOwner}. The single {@link #lawful} boolean is the jail-vs-kidnapping switch
 * (spec §1.4, §8.1): escaping lawful custody is a crime; escaping kidnapping never is. {@link
 * #realTicksHeld} is the real-online-time accumulator for the §7.2 captivity-cap backstop (mirrors {@code
 * JailState.realOnlineTicksServed}).
 *
 * <p>Pure data + NBT (no config/server deps) so it round-trips in unit tests; {@link #load} of an empty tag
 * never throws (nulls/defaults), keeping a hand-edited or partial save from softlocking or crashing.
 */
public final class CustodyRecord {

    private UUID captive;
    private boolean captiveIsPlayer;
    private boolean lawful;
    private CustodyOwner owner = CustodyOwner.none();
    private RestraintType restraint = RestraintType.NONE;
    /** The captor's online-tick clock value at capture (provenance / debugging). */
    private long startTickOnline;
    /** Lawful custody only: the mirrored sentence (the authority is {@code JailService}; this is a projection). */
    private long remainingJailTicks;
    /** Assigned at arrest, even when the sentence has no cases or no generated cell. */
    @Nullable
    private UUID sentenceId;
    /** Real online ticks the captive has been held, for the §7.2 captivity cap. */
    private long realTicksHeld;
    @Nullable
    private BlockPos holdPos;
    @Nullable
    private ResourceLocation holdDim;
    /** True when the captive's chunk is unloaded and they are virtually contained (spec §7.5). */
    private boolean virtual;
    private boolean escapeActive;
    private int escapeProgress;
    private long escapeCooldownUntil;
    private double escapeRoll = 1.0D;
    private int escapeAttempts;
    private long captorDisconnectedAt;
    private byte[] cuffCombination = new byte[0];

    public byte[] getCuffCombination() { return cuffCombination.clone(); }
    public void setCuffCombination(byte[] pins) {
        cuffCombination = CuffLockProgress.validCombination(pins) ? pins.clone() : new byte[0];
    }

    public CustodyRecord() {
    }

    public CustodyRecord(UUID captive, boolean captiveIsPlayer, boolean lawful, CustodyOwner owner,
                         RestraintType restraint, long startTickOnline, @Nullable BlockPos holdPos,
                         @Nullable ResourceLocation holdDim) {
        this.captive = captive;
        this.captiveIsPlayer = captiveIsPlayer;
        this.lawful = lawful;
        this.owner = owner;
        this.restraint = restraint;
        this.startTickOnline = startTickOnline;
        this.holdPos = holdPos;
        this.holdDim = holdDim;
    }

    public UUID getCaptive() {
        return captive;
    }

    public boolean isCaptivePlayer() {
        return captiveIsPlayer;
    }

    public boolean isLawful() {
        return lawful;
    }

    public CustodyOwner getOwner() {
        return owner;
    }

    public void setOwner(CustodyOwner owner) {
        this.owner = owner;
    }

    public RestraintType getRestraint() {
        return restraint;
    }

    public void setRestraint(RestraintType restraint) {
        this.restraint = restraint;
    }

    public long getStartTickOnline() {
        return startTickOnline;
    }

    public long getRemainingJailTicks() {
        return remainingJailTicks;
    }

    public void setRemainingJailTicks(long remainingJailTicks) {
        this.remainingJailTicks = Math.max(0L, remainingJailTicks);
    }

    @Nullable
    public UUID getSentenceId() { return sentenceId; }

    /** Null is reserved for custody written before arrest-time sentence assignment. */
    public void setSentenceId(UUID sentenceId) { this.sentenceId = sentenceId; }

    public long getRealTicksHeld() {
        return realTicksHeld;
    }

    public void setRealTicksHeld(long realTicksHeld) {
        this.realTicksHeld = realTicksHeld;
    }

    @Nullable
    public BlockPos getHoldPos() {
        return holdPos;
    }

    public void setHoldPos(@Nullable BlockPos holdPos) {
        this.holdPos = holdPos;
    }

    @Nullable
    public ResourceLocation getHoldDim() {
        return holdDim;
    }

    public boolean isVirtual() {
        return virtual;
    }

    public void setVirtual(boolean virtual) {
        this.virtual = virtual;
    }

    public boolean isEscapeActive() { return escapeActive; }
    public void setEscapeActive(boolean value) { escapeActive = value; }
    public int getEscapeProgress() { return escapeProgress; }
    public void setEscapeProgress(int value) { escapeProgress = Math.max(0, value); }
    public long getEscapeCooldownUntil() { return escapeCooldownUntil; }
    public void setEscapeCooldownUntil(long value) { escapeCooldownUntil = Math.max(0L, value); }
    public double getEscapeRoll() { return escapeRoll; }
    public void setEscapeRoll(double value) { escapeRoll = Math.max(0.0D, Math.min(1.0D, value)); }
    public int getEscapeAttempts() { return escapeAttempts; }
    public void setEscapeAttempts(int value) { escapeAttempts = Math.max(0, value); }
    public long getCaptorDisconnectedAt() { return captorDisconnectedAt; }
    public void setCaptorDisconnectedAt(long value) { captorDisconnectedAt = Math.max(0L, value); }

    /** True when this custody has a resolvable hold location (otherwise soft-tether is impossible). */
    public boolean hasValidHold() {
        return holdPos != null && holdDim != null;
    }

    public CustodyRecord copy() {
        CustodyRecord c = new CustodyRecord();
        c.captive = captive; // UUID is immutable
        c.captiveIsPlayer = captiveIsPlayer;
        c.lawful = lawful;
        c.owner = owner; // CustodyOwner is immutable
        c.restraint = restraint;
        c.startTickOnline = startTickOnline;
        c.remainingJailTicks = remainingJailTicks;
        c.sentenceId = sentenceId;
        c.realTicksHeld = realTicksHeld;
        c.holdPos = holdPos; // BlockPos is immutable
        c.holdDim = holdDim; // ResourceLocation is immutable
        c.virtual = virtual;
        c.escapeActive = escapeActive;
        c.escapeProgress = escapeProgress;
        c.escapeCooldownUntil = escapeCooldownUntil;
        c.escapeRoll = escapeRoll;
        c.escapeAttempts = escapeAttempts;
        c.captorDisconnectedAt = captorDisconnectedAt;
        c.cuffCombination = cuffCombination.clone();
        return c;
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        if (captive != null) {
            tag.putUUID("captive", captive);
        }
        tag.putBoolean("player", captiveIsPlayer);
        tag.putBoolean("lawful", lawful);
        tag.put("owner", owner.save());
        tag.putString("restraint", restraint.name());
        tag.putLong("start", startTickOnline);
        tag.putLong("remaining", remainingJailTicks);
        if (sentenceId != null) tag.putUUID("sentenceId", sentenceId);
        tag.putLong("held", realTicksHeld);
        if (holdPos != null) {
            tag.putInt("hx", holdPos.getX());
            tag.putInt("hy", holdPos.getY());
            tag.putInt("hz", holdPos.getZ());
        }
        if (holdDim != null) {
            tag.putString("hdim", holdDim.toString());
        }
        tag.putBoolean("virtual", virtual);
        tag.putBoolean("escapeActive", escapeActive);
        tag.putInt("escapeProgress", escapeProgress);
        tag.putLong("escapeCooldownUntil", escapeCooldownUntil);
        tag.putDouble("escapeRoll", escapeRoll);
        tag.putInt("escapeAttempts", escapeAttempts);
        tag.putLong("captorDisconnectedAt", captorDisconnectedAt);
        if (cuffCombination.length > 0) tag.putByteArray("cuffCombination", cuffCombination);
        return tag;
    }

    public static CustodyRecord load(CompoundTag tag) {
        CustodyRecord r = new CustodyRecord();
        r.captive = tag.hasUUID("captive") ? tag.getUUID("captive") : null;
        r.captiveIsPlayer = tag.getBoolean("player");
        r.lawful = tag.getBoolean("lawful");
        r.owner = CustodyOwner.load(tag.getCompound("owner"));
        r.restraint = RestraintType.parse(tag.getString("restraint"));
        r.startTickOnline = tag.getLong("start");
        r.remainingJailTicks = Math.max(0L, tag.getLong("remaining"));
        r.sentenceId = tag.hasUUID("sentenceId") ? tag.getUUID("sentenceId") : null;
        r.realTicksHeld = tag.getLong("held");
        if (tag.contains("hx") && tag.contains("hy") && tag.contains("hz")) {
            r.holdPos = new BlockPos(tag.getInt("hx"), tag.getInt("hy"), tag.getInt("hz"));
        }
        r.holdDim = tag.contains("hdim") ? ResourceLocation.tryParse(tag.getString("hdim")) : null;
        r.virtual = tag.getBoolean("virtual");
        r.escapeActive = tag.getBoolean("escapeActive");
        r.escapeProgress = Math.max(0, tag.getInt("escapeProgress"));
        r.escapeCooldownUntil = Math.max(0L, tag.getLong("escapeCooldownUntil"));
        r.escapeRoll = tag.contains("escapeRoll") ? Math.max(0.0D, Math.min(1.0D, tag.getDouble("escapeRoll"))) : 1.0D;
        r.escapeAttempts = Math.max(0, tag.getInt("escapeAttempts"));
        r.captorDisconnectedAt = Math.max(0L, tag.getLong("captorDisconnectedAt"));
        r.setCuffCombination(tag.getByteArray("cuffCombination"));
        return r;
    }
}
