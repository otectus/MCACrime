package dev.otectus.mcacrime.enforcement;

import dev.otectus.mcacrime.jail.JailAnchor;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;

import javax.annotation.Nullable;
import java.util.UUID;

/**
 * A player's in-flight arrest. The structural twin of {@link dev.otectus.mcacrime.jail.JailState}:
 * pure data plus NBT, no config or server dependency, so it round-trips in a unit test and
 * {@link #load} of an empty tag never throws.
 *
 * <p>Held inside {@link dev.otectus.mcacrime.state.PlayerCrimeData} (null = no arrest) and written to
 * the player's NBT, which is what makes an arrest survive a logout, a death, and a restart. Before
 * this existed the arrest lived in two static maps that a restart simply erased, so the answer to
 * "can reconnecting bypass the sentence" depended on which of five stores you asked.
 *
 * <p><b>Clocks are online ticks</b> ({@code PlayerCrimeData.getOnlineTicksLived}), not game time,
 * matching {@code resistingArrestUntilTick} and the sentence itself. An offline player's escort
 * deadline and recovery window therefore do not run down, so quitting for a minute is not a way to
 * wait either of them out.
 */
public final class ArrestState {

    private ArrestPhase phase = ArrestPhase.NONE;
    /** Who owns this arrest: the challenging guard, then the escorting one. Null once the jail has them. */
    @Nullable
    private UUID guard;
    /** The encounter that started it, so a replayed challenge response cannot re-enter the flow. */
    @Nullable
    private UUID encounterId;
    @Nullable
    private BlockPos anchorPos;
    @Nullable
    private ResourceLocation anchorDim;
    private int anchorRadius;
    /**
     * The sentence assessed at arrest time, applied on arrival.
     *
     * <p>Stored rather than recomputed on arrival, because Heat decays: recomputing would mean a slow
     * walk, or a logout mid-escort, quietly shortened a sentence that had already been assessed.
     */
    private long sentenceTicks;
    private boolean surrenderCredited;
    /** Minted once here and shared with the holding cell and the sentence, so the three can be matched. */
    private UUID sentenceId = UUID.randomUUID();
    /** Escort timeout, or recovery expiry, on the player's own online clock. */
    private long deadlineOnlineTick;
    /** Game time of the last navigation order, so the escort reissues on a cadence and never per tick. */
    private long lastNavigationTick;
    /** Closest the prisoner has been to the destination, for stuck detection. */
    private double bestAnchorDistanceSqr = Double.MAX_VALUE;
    private int stuckStrikes;
    /** Where the prisoner was at the last escort scan, to tell a teleport from a sprint. */
    @Nullable
    private BlockPos lastSeenPos;

    public ArrestState() {
    }

    public ArrestPhase getPhase() {
        return phase;
    }

    /** Package-private on purpose: {@link ArrestStates} is the only writer, and it validates the edge. */
    void setPhase(ArrestPhase phase) {
        this.phase = phase == null ? ArrestPhase.NONE : phase;
    }

    @Nullable
    public UUID getGuard() {
        return guard;
    }

    public void setGuard(@Nullable UUID guard) {
        this.guard = guard;
    }

    @Nullable
    public UUID getEncounterId() {
        return encounterId;
    }

    public void setEncounterId(@Nullable UUID encounterId) {
        this.encounterId = encounterId;
    }

    public void setAnchor(@Nullable JailAnchor anchor) {
        this.anchorPos = anchor == null ? null : anchor.pos();
        this.anchorDim = anchor == null ? null : anchor.dim();
        this.anchorRadius = anchor == null ? 0 : anchor.radius();
    }

    /** The destination, or null when none was ever resolved or the stored one is incomplete. */
    @Nullable
    public JailAnchor anchor() {
        return anchorPos == null || anchorDim == null
                ? null
                : new JailAnchor(anchorPos, anchorDim, anchorRadius);
    }

    public long getSentenceTicks() {
        return sentenceTicks;
    }

    public boolean isSurrenderCredited() { return surrenderCredited; }
    public void setSurrenderCredited(boolean credited) { surrenderCredited = credited; }

    public void setSentenceTicks(long sentenceTicks) {
        this.sentenceTicks = Math.max(0L, sentenceTicks);
    }

    public UUID getSentenceId() {
        return sentenceId;
    }

    public long getDeadlineOnlineTick() {
        return deadlineOnlineTick;
    }

    public void setDeadlineOnlineTick(long deadlineOnlineTick) {
        this.deadlineOnlineTick = Math.max(0L, deadlineOnlineTick);
    }

    /** True when the escort timeout or recovery window has run out on the player's own clock. */
    public boolean expired(long onlineTicksLived) {
        return deadlineOnlineTick > 0L && onlineTicksLived >= deadlineOnlineTick;
    }

    public long getLastNavigationTick() {
        return lastNavigationTick;
    }

    public void setLastNavigationTick(long lastNavigationTick) {
        this.lastNavigationTick = lastNavigationTick;
    }

    public double getBestAnchorDistanceSqr() {
        return bestAnchorDistanceSqr;
    }

    public void setBestAnchorDistanceSqr(double bestAnchorDistanceSqr) {
        this.bestAnchorDistanceSqr = bestAnchorDistanceSqr;
    }

    public int getStuckStrikes() {
        return stuckStrikes;
    }

    public void setStuckStrikes(int stuckStrikes) {
        this.stuckStrikes = Math.max(0, stuckStrikes);
    }

    @Nullable
    public BlockPos getLastSeenPos() {
        return lastSeenPos;
    }

    public void setLastSeenPos(@Nullable BlockPos lastSeenPos) {
        this.lastSeenPos = lastSeenPos;
    }

    /** Whether the player is physically restrained right now. */
    public boolean isRestraining() {
        return ArrestPhases.isRestrained(phase);
    }

    public ArrestState copy() {
        ArrestState c = new ArrestState();
        c.phase = phase;
        c.guard = guard;               // UUID is immutable
        c.encounterId = encounterId;
        c.anchorPos = anchorPos;       // BlockPos is immutable
        c.anchorDim = anchorDim;       // ResourceLocation is immutable
        c.anchorRadius = anchorRadius;
        c.sentenceTicks = sentenceTicks;
        c.surrenderCredited = surrenderCredited;
        c.sentenceId = sentenceId;
        c.deadlineOnlineTick = deadlineOnlineTick;
        c.lastNavigationTick = lastNavigationTick;
        c.bestAnchorDistanceSqr = bestAnchorDistanceSqr;
        c.stuckStrikes = stuckStrikes;
        c.lastSeenPos = lastSeenPos;
        return c;
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putString("phase", phase.name());
        if (guard != null) {
            tag.putUUID("guard", guard);
        }
        if (encounterId != null) {
            tag.putUUID("encounter", encounterId);
        }
        if (anchorPos != null) {
            tag.putInt("ax", anchorPos.getX());
            tag.putInt("ay", anchorPos.getY());
            tag.putInt("az", anchorPos.getZ());
        }
        if (anchorDim != null) {
            tag.putString("adim", anchorDim.toString());
        }
        tag.putInt("aradius", anchorRadius);
        tag.putLong("sentence", sentenceTicks);
        if (surrenderCredited) tag.putBoolean("surrenderCredited", true);
        tag.putUUID("sentenceId", sentenceId);
        tag.putLong("deadline", deadlineOnlineTick);
        if (lastSeenPos != null) {
            tag.putInt("lx", lastSeenPos.getX());
            tag.putInt("ly", lastSeenPos.getY());
            tag.putInt("lz", lastSeenPos.getZ());
        }
        // lastNavigationTick, bestAnchorDistanceSqr and stuckStrikes are deliberately not saved: they
        // describe one guard's progress on one walk, and a restart does not resume that walk.
        return tag;
    }

    public static ArrestState load(CompoundTag tag) {
        ArrestState s = new ArrestState();
        s.phase = ArrestPhase.parse(tag.getString("phase"));
        s.guard = tag.hasUUID("guard") ? tag.getUUID("guard") : null;
        s.encounterId = tag.hasUUID("encounter") ? tag.getUUID("encounter") : null;
        if (tag.contains("ax") && tag.contains("ay") && tag.contains("az")) {
            s.anchorPos = new BlockPos(tag.getInt("ax"), tag.getInt("ay"), tag.getInt("az"));
        }
        s.anchorDim = tag.contains("adim") ? ResourceLocation.tryParse(tag.getString("adim")) : null;
        s.anchorRadius = tag.getInt("aradius");
        s.sentenceTicks = Math.max(0L, tag.getLong("sentence"));
        s.surrenderCredited = tag.getBoolean("surrenderCredited");
        if (tag.hasUUID("sentenceId")) {
            s.sentenceId = tag.getUUID("sentenceId");
        }
        s.deadlineOnlineTick = Math.max(0L, tag.getLong("deadline"));
        if (tag.contains("lx") && tag.contains("ly") && tag.contains("lz")) {
            s.lastSeenPos = new BlockPos(tag.getInt("lx"), tag.getInt("ly"), tag.getInt("lz"));
        }
        return s;
    }
}
