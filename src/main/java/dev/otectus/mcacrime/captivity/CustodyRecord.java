package dev.otectus.mcacrime.captivity;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;

import javax.annotation.Nullable;
import java.nio.charset.StandardCharsets;
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

    /**
     * This captivity's own identity (0.7.5 §6.2).
     *
     * <p>Neither the captive nor the sentence can stand in for it. An unlawful capture has no
     * sentence at all, and two successive captures of the same person share a captive UUID -- so a
     * delayed packet or a stale session from the first would be accepted against the second. This is
     * what those are pinned to.
     *
     * <p>Allocated once, at capture, and never rewritten: a custody that changes hands is the same
     * custody, which is what {@link #generation} is for.
     */
    @Nullable
    private UUID custodyId;

    /**
     * Bumped every time this custody changes hands (guard to jail, guard to guard).
     *
     * <p>The id says which captivity; the generation says which phase of it. An escort packet issued
     * to the arresting guard must stop working the moment the jail takes over, and the alternative --
     * releasing and re-capturing -- would fire the public events twice and briefly free the prisoner.
     */
    private long generation = 1L;

    private UUID captive;
    private boolean captiveIsPlayer;
    private boolean lawful;
    private CustodyOwner owner = CustodyOwner.none();
    /**
     * What a pre-0.7.5 row said was on this captive, and nothing else (0.7.5 §3.2, §3.18).
     *
     * <p>Physical restraint lives in {@code state/world/CrimeWorldData}'s {@code physicalRestraints}
     * table now, keyed by the same subject UUID this record is keyed by -- so the reference from a
     * captivity to the gear on the captive is the captive themselves, and there is nothing to keep in
     * sync. This field survives only as <em>migration input</em>: {@code
     * restraint/RestraintMigrationReconciler} reads it once, converts it into a real worn instance,
     * and it is never written back.
     */
    @Deprecated
    private RestraintType legacyRestraint = RestraintType.NONE;
    /** The captor's online-tick clock value at capture (provenance / debugging). */
    private long startTickOnline;
    /** Lawful custody only: the mirrored sentence (the authority is {@code JailService}; this is a projection). */
    private long remainingJailTicks;
    /** Assigned at arrest, even when the sentence has no cases or no generated cell. */
    @Nullable
    private UUID sentenceId;
    /**
     * What that sentence is (0.7.5 §3.19), mirrored here from the world's sentence-kind table.
     *
     * <p>A mirror rather than the authority, and the distinction matters: the table is keyed by
     * sentence id and is what clemency rewrites, while this is what the confinement, ransom and bail
     * paths read without a second lookup for every captive on every tick. It is written when the
     * sentence is bound and again when it is commuted, and absent reads as custodial -- which is what
     * every custody record written before this release was.
     */
    private dev.otectus.mcacrime.ledger.SentenceKind sentenceKind =
            dev.otectus.mcacrime.ledger.SentenceKind.CUSTODIAL;
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
    /**
     * Custody-recovery (§8.6): confinement is suspended so the captive can recover, and the sentence,
     * the sentence id and the case set all stay exactly where they are.
     *
     * <p>A state on the record rather than a release reason, and that distinction is the whole point.
     * Releasing a critically unfit prisoner through {@code SENTENCE_SERVED} or an admin release would
     * clear a liability nobody discharged: they have not finished their sentence and nobody pardoned
     * them, they were let out of a cell so they could eat. Absent in every record written before 0.7.3,
     * and absent reads as false.
     */
    private boolean recovery;
    private String recoveryReason = "";
    private long recoverySince;
    /**
     * The retired pre-0.7.5 cuff combination, archived and cleared by the reconciler.
     *
     * <p>Kept readable only so the upgrade can file it under {@code reserved}; it unlocks nothing.
     */
    private byte[] cuffCombination = new byte[0];

    public byte[] getCuffCombination() { return cuffCombination.clone(); }

    /**
     * Accepts a legacy combination if it is well formed: three to eight pins, each in range and each
     * used once. Anything else loads as "no combination", which is what an unreadable one means.
     */
    public void setCuffCombination(byte[] pins) {
        cuffCombination = validCombination(pins) ? pins.clone() : new byte[0];
    }

    private static boolean validCombination(byte[] pins) {
        if (pins == null || pins.length < 3 || pins.length > 8) {
            return false;
        }
        int seen = 0;
        for (byte pin : pins) {
            if (pin < 0 || pin >= pins.length || (seen & (1 << pin)) != 0) {
                return false;
            }
            seen |= 1 << pin;
        }
        return true;
    }

    public CustodyRecord() {
    }

    /**
     * The custody id a pre-0.7.5 record is given.
     *
     * <p>Derived from the captive rather than random, and that is the whole point: the schema 14 to
     * 15 migration and a direct {@link #load} of an un-migrated row must agree on it, and loading the
     * same file twice must not produce two different identities for one captivity.
     */
    public static UUID legacyCustodyId(UUID captive) {
        return UUID.nameUUIDFromBytes(("mcacrime:custody:" + captive).getBytes(StandardCharsets.UTF_8));
    }

    public CustodyRecord(UUID captive, boolean captiveIsPlayer, boolean lawful, CustodyOwner owner,
                         long startTickOnline, @Nullable BlockPos holdPos,
                         @Nullable ResourceLocation holdDim) {
        // A fresh capture is a fresh captivity: new identity, first generation.
        this.custodyId = UUID.randomUUID();
        this.generation = 1L;
        this.captive = captive;
        this.captiveIsPlayer = captiveIsPlayer;
        this.lawful = lawful;
        this.owner = owner;
        this.startTickOnline = startTickOnline;
        this.holdPos = holdPos;
        this.holdDim = holdDim;
    }

    /** This captivity's identity. Null only for a record loaded from a row that named no captive. */
    @Nullable
    public UUID getCustodyId() {
        return custodyId;
    }

    public long getGeneration() {
        return generation;
    }

    /**
     * Advances the generation, refusing every packet and session bound to the previous holder.
     *
     * @return the new generation
     */
    public long bumpGeneration() {
        generation = Math.max(1L, generation) + 1L;
        return generation;
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

    /**
     * What a pre-0.7.5 row recorded, for the migration and for the deprecated API projections only.
     *
     * <p>Not authoritative and never will be again: ask
     * {@code CrimeWorldData.physicalRestraint(getCaptive())} what is actually on this captive.
     */
    @Deprecated
    public RestraintType getLegacyRestraint() {
        return legacyRestraint;
    }

    /** Migration input only: the reconciler clears this once it has converted the row. */
    @Deprecated
    public void setLegacyRestraint(RestraintType restraint) {
        this.legacyRestraint = restraint == null ? RestraintType.NONE : restraint;
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

    /** What this captive's sentence is. Custodial unless a capital binding named them (§3.19). */
    public dev.otectus.mcacrime.ledger.SentenceKind getSentenceKind() {
        return sentenceKind == null ? dev.otectus.mcacrime.ledger.SentenceKind.CUSTODIAL : sentenceKind;
    }

    /** Mirrors the sentence-kind table onto this record. The table stays the authority. */
    public void setSentenceKind(@Nullable dev.otectus.mcacrime.ledger.SentenceKind kind) {
        this.sentenceKind = kind == null ? dev.otectus.mcacrime.ledger.SentenceKind.CUSTODIAL : kind;
    }

    /** True while a live capital sentence names this captive. */
    public boolean isCondemned() {
        return getSentenceKind().capital();
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

    /** Whether confinement is currently suspended for recovery. The sentence is unaffected. */
    public boolean isInRecovery() {
        return recovery;
    }

    public String getRecoveryReason() {
        return recoveryReason;
    }

    /** The game time recovery began, for the operator-visible record. */
    public long getRecoverySince() {
        return recoverySince;
    }

    /**
     * Suspends confinement for care.
     *
     * @return true when this call is what changed the state, so the caller logs once rather than every tick
     */
    public boolean enterRecovery(String reason, long gameTime) {
        this.recoveryReason = reason == null ? "" : reason;
        if (recovery) {
            return false;
        }
        recovery = true;
        recoverySince = gameTime;
        return true;
    }

    /** Ends recovery and returns the captive to ordinary custody. */
    public boolean exitRecovery() {
        if (!recovery) {
            return false;
        }
        recovery = false;
        recoveryReason = "";
        recoverySince = 0L;
        return true;
    }

    /** True when this custody has a resolvable hold location (otherwise soft-tether is impossible). */
    public boolean hasValidHold() {
        return holdPos != null && holdDim != null;
    }

    public CustodyRecord copy() {
        CustodyRecord c = new CustodyRecord();
        c.custodyId = custodyId; // UUID is immutable
        c.generation = generation;
        c.captive = captive; // UUID is immutable
        c.captiveIsPlayer = captiveIsPlayer;
        c.lawful = lawful;
        c.owner = owner; // CustodyOwner is immutable
        c.legacyRestraint = legacyRestraint;
        c.startTickOnline = startTickOnline;
        c.remainingJailTicks = remainingJailTicks;
        c.sentenceId = sentenceId;
        c.sentenceKind = sentenceKind;
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
        c.recovery = recovery;
        c.recoveryReason = recoveryReason;
        c.recoverySince = recoverySince;
        c.cuffCombination = cuffCombination.clone();
        return c;
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        if (custodyId != null) {
            tag.putUUID("custodyId", custodyId);
        }
        tag.putLong("generation", generation);
        if (captive != null) {
            tag.putUUID("captive", captive);
        }
        tag.putBoolean("player", captiveIsPlayer);
        tag.putBoolean("lawful", lawful);
        tag.put("owner", owner.save());
        // Written only while the reconciler has not yet converted it. A row whose gear has become a
        // real worn instance must not keep a second, stale answer to the same question.
        if (legacyRestraint != RestraintType.NONE) {
            tag.putString("restraint", legacyRestraint.name());
        }
        tag.putLong("start", startTickOnline);
        tag.putLong("remaining", remainingJailTicks);
        if (sentenceId != null) tag.putUUID("sentenceId", sentenceId);
        // Written only when it is not the default, so an ordinary custody row is unchanged (§3.19).
        if (sentenceKind != null && sentenceKind.capital()) {
            tag.putString("sentenceKind", sentenceKind.name());
        }
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
        // Written only while it is true: a record that never recovered says nothing, and absent already
        // reads as "not in recovery" on every older build.
        if (recovery) {
            tag.putBoolean("recovery", true);
            tag.putString("recoveryReason", recoveryReason);
            tag.putLong("recoverySince", recoverySince);
        }
        if (cuffCombination.length > 0) tag.putByteArray("cuffCombination", cuffCombination);
        return tag;
    }

    public static CustodyRecord load(CompoundTag tag) {
        CustodyRecord r = new CustodyRecord();
        r.captive = tag.hasUUID("captive") ? tag.getUUID("captive") : null;
        // A row written before 0.7.5 has no identity. Deriving one from the captive rather than
        // minting a random one means the same file always loads to the same custody id, whether it
        // came through the migration or straight off disk.
        r.custodyId = tag.hasUUID("custodyId") ? tag.getUUID("custodyId")
                : r.captive == null ? null : legacyCustodyId(r.captive);
        r.generation = Math.max(1L, tag.getLong("generation"));
        r.captiveIsPlayer = tag.getBoolean("player");
        r.lawful = tag.getBoolean("lawful");
        r.owner = CustodyOwner.load(tag.getCompound("owner"));
        r.legacyRestraint = RestraintType.parse(tag.getString("restraint"));
        r.startTickOnline = tag.getLong("start");
        r.remainingJailTicks = Math.max(0L, tag.getLong("remaining"));
        r.sentenceId = tag.hasUUID("sentenceId") ? tag.getUUID("sentenceId") : null;
        r.sentenceKind = dev.otectus.mcacrime.ledger.SentenceKind.parseOr(tag.getString("sentenceKind"),
                dev.otectus.mcacrime.ledger.SentenceKind.CUSTODIAL);
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
        r.recovery = tag.getBoolean("recovery");
        r.recoveryReason = tag.getString("recoveryReason");
        r.recoverySince = Math.max(0L, tag.getLong("recoverySince"));
        r.setCuffCombination(tag.getByteArray("cuffCombination"));
        return r;
    }
}
