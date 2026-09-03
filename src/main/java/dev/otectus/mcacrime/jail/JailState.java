package dev.otectus.mcacrime.jail;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;

import org.jetbrains.annotations.Nullable;
import java.util.UUID;

/**
 * A player's active jail sentence (spec §2.1, §7). Held inside {@link dev.otectus.mcacrime.state.PlayerCrimeData}
 * (null = not jailed) and serialized to the player's NBT, so the sentence survives logout, death (copied on
 * {@code PlayerEvent.Clone}), dimension change, and restart (spec §7.1).
 *
 * <p>The sentence is measured in <b>online ticks</b> ({@link #remainingOnlineTicks}) decremented only while
 * the player is online and loaded — so logout pauses it and it can't be waited out offline. {@link #realOnlineTicksServed}
 * is the independent real-online-time accumulator for the §7.2 captivity-cap failsafe. {@link #modeSnapshot}
 * captures the containment mode at jail time so a mid-sentence config flip can't surprise a prisoner.
 *
 * <p>Pure data + NBT (no config/server deps) so it round-trips in unit tests; {@link #load} of an empty tag
 * never throws (nulls/defaults), which keeps a hand-edited or partial save from softlocking or crashing.
 */
public final class JailState {

    private long remainingOnlineTicks;
    private long realOnlineTicksServed;
    @Nullable
    private BlockPos jailAnchor;
    @Nullable
    private ResourceLocation jailDim;
    private int jailRadius;
    private JailContainmentMode modeSnapshot = JailContainmentMode.CONTAINMENT;
    /** PHYSICAL breakout flag: drives Legal Target. The sentence still continues while escaped. */
    private boolean escaped;
    /**
     * Identity of this sentence, so the cases it settles can name it and a replayed release settles
     * nothing twice. Minted once when the sentence starts and never reused; a sentence that is
     * extended keeps its original id, because it is still the same stretch of time being served.
     */
    private UUID sentenceId = UUID.randomUUID();

    public JailState() {
    }

    public JailState(long remainingOnlineTicks, @Nullable BlockPos jailAnchor, @Nullable ResourceLocation jailDim,
                     int jailRadius, JailContainmentMode modeSnapshot) {
        this.remainingOnlineTicks = remainingOnlineTicks;
        this.jailAnchor = jailAnchor;
        this.jailDim = jailDim;
        this.jailRadius = jailRadius;
        this.modeSnapshot = modeSnapshot;
    }

    public long getRemainingOnlineTicks() {
        return remainingOnlineTicks;
    }

    public void setRemainingOnlineTicks(long remainingOnlineTicks) {
        this.remainingOnlineTicks = remainingOnlineTicks;
    }

    public long getRealOnlineTicksServed() {
        return realOnlineTicksServed;
    }

    public void setRealOnlineTicksServed(long realOnlineTicksServed) {
        this.realOnlineTicksServed = realOnlineTicksServed;
    }

    @Nullable
    public BlockPos getJailAnchor() {
        return jailAnchor;
    }

    @Nullable
    public ResourceLocation getJailDim() {
        return jailDim;
    }

    public int getJailRadius() {
        return jailRadius;
    }

    public JailContainmentMode getModeSnapshot() {
        return modeSnapshot;
    }

    public UUID getSentenceId() {
        return sentenceId;
    }

    /**
     * Adopts an id minted earlier in the arrest.
     *
     * <p>The holding cell is built at the start of an arrest and the sentence only begins when the
     * escort arrives, so the two were minted independently and never matched -- which made
     * {@code HoldingCell.sentenceId} a field whose documented purpose (telling a live cell from an
     * orphan across a restart) nothing could ever use. Threading one id through both makes the
     * invariant real.
     */
    public void setSentenceId(UUID sentenceId) {
        if (sentenceId != null) {
            this.sentenceId = sentenceId;
        }
    }

    public boolean isEscaped() {
        return escaped;
    }

    public void setEscaped(boolean escaped) {
        this.escaped = escaped;
    }

    /** True when this sentence has a resolvable anchor + dimension (otherwise soft-confine is impossible). */
    public boolean hasValidAnchor() {
        return jailAnchor != null && jailDim != null;
    }

    public JailState copy() {
        JailState c = new JailState();
        c.remainingOnlineTicks = remainingOnlineTicks;
        c.realOnlineTicksServed = realOnlineTicksServed;
        c.jailAnchor = jailAnchor; // BlockPos is immutable
        c.jailDim = jailDim;       // ResourceLocation is immutable
        c.jailRadius = jailRadius;
        c.modeSnapshot = modeSnapshot;
        c.escaped = escaped;
        c.sentenceId = sentenceId;
        return c;
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putLong("remaining", remainingOnlineTicks);
        tag.putLong("served", realOnlineTicksServed);
        if (jailAnchor != null) {
            tag.putInt("ax", jailAnchor.getX());
            tag.putInt("ay", jailAnchor.getY());
            tag.putInt("az", jailAnchor.getZ());
        }
        if (jailDim != null) {
            tag.putString("dim", jailDim.toString());
        }
        tag.putInt("radius", jailRadius);
        tag.putString("mode", modeSnapshot.name());
        tag.putBoolean("escaped", escaped);
        tag.putUUID("sentenceId", sentenceId);
        return tag;
    }

    public static JailState load(CompoundTag tag) {
        JailState s = new JailState();
        s.remainingOnlineTicks = tag.getLong("remaining");
        s.realOnlineTicksServed = tag.getLong("served");
        if (tag.contains("ax") && tag.contains("ay") && tag.contains("az")) {
            s.jailAnchor = new BlockPos(tag.getInt("ax"), tag.getInt("ay"), tag.getInt("az"));
        }
        s.jailDim = tag.contains("dim") ? ResourceLocation.tryParse(tag.getString("dim")) : null;
        s.jailRadius = tag.getInt("radius");
        s.modeSnapshot = JailContainmentMode.parse(tag.getString("mode"));
        s.escaped = tag.getBoolean("escaped");
        // A sentence saved before this field existed keeps the fresh id minted in the field
        // initialiser. That is correct: the id only has to be unique, never to match a past value.
        if (tag.hasUUID("sentenceId")) {
            s.sentenceId = tag.getUUID("sentenceId");
        }
        return s;
    }
}
