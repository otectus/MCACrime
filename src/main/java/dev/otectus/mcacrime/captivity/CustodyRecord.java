package dev.otectus.mcacrime.captivity;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;

import javax.annotation.Nullable;
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
    /** Real online ticks the captive has been held, for the §7.2 captivity cap. */
    private long realTicksHeld;
    @Nullable
    private BlockPos holdPos;
    @Nullable
    private ResourceLocation holdDim;
    /** True when the captive's chunk is unloaded and they are virtually contained (spec §7.5). */
    private boolean virtual;

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
        this.remainingJailTicks = remainingJailTicks;
    }

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
        c.realTicksHeld = realTicksHeld;
        c.holdPos = holdPos; // BlockPos is immutable
        c.holdDim = holdDim; // ResourceLocation is immutable
        c.virtual = virtual;
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
        r.remainingJailTicks = tag.getLong("remaining");
        r.realTicksHeld = tag.getLong("held");
        if (tag.contains("hx") && tag.contains("hy") && tag.contains("hz")) {
            r.holdPos = new BlockPos(tag.getInt("hx"), tag.getInt("hy"), tag.getInt("hz"));
        }
        r.holdDim = tag.contains("hdim") ? ResourceLocation.tryParse(tag.getString("hdim")) : null;
        r.virtual = tag.getBoolean("virtual");
        return r;
    }
}
