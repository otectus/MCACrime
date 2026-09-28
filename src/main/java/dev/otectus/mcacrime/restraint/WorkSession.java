package dev.otectus.mcacrime.restraint;

import net.minecraft.resources.ResourceLocation;

import javax.annotation.Nullable;
import java.util.UUID;

/**
 * A bounded piece of server-scored effort: struggling out of a restraint, working a device's latch.
 *
 * <p>Holds the two things the server needs to reject an impossible input rate and a replayed one:
 * the tick the last input was accepted at, and the sequence number it carried. Both live here rather
 * than on the packet, because a value the client supplies is a value the client chooses.
 */
public final class WorkSession extends Session {

    private int progress;
    private int lastInputSeq = -1;
    private long lastInputTick = Long.MIN_VALUE;
    private int lastInputKind = -1;
    private long cooldownUntilTick = Long.MIN_VALUE;
    @Nullable
    private RestraintSlot slot;

    public WorkSession(long id, UUID actor, @Nullable UUID target, long targetRevision,
                       @Nullable ResourceLocation dimension, @Nullable ResourceLocation sourceItem,
                       long expiryTick, @Nullable RestraintSlot slot) {
        super(id, SessionKind.WORK, actor, target, targetRevision, dimension, sourceItem, expiryTick);
        this.slot = slot;
    }

    public int progress() {
        return progress;
    }

    @Nullable
    public RestraintSlot slot() {
        return slot;
    }

    public int lastInputSeq() {
        return lastInputSeq;
    }

    public long lastInputTick() {
        return lastInputTick;
    }

    /**
     * Accepts one input if it strictly advances and the minimum interval has passed.
     *
     * <p>Both conditions, not either: the sequence stops a replay, the interval stops a macro. The
     * cooldown is stored <em>and</em> compared, which is the upstream bug this shape exists to avoid
     * — its break cooldown is written and never decremented, so it never applies.
     *
     * @return true when the input counted
     */
    public boolean acceptInput(int inputSeq, long now, int minIntervalTicks) {
        return acceptInput(inputSeq, now, minIntervalTicks, -1, false);
    }

    /**
     * The struggle form: sequence, interval, alternation and cooldown, in that order.
     *
     * <p>Alternation is what makes struggling a two-handed effort rather than one held key, and it is
     * checked on the server because the source checks it on the client
     * ({@code AbstractRestraint.attemptToBreak} compares key codes in a client screen) where a modified
     * client simply does not.
     *
     * @param inputKind        the input this arrival carries, or -1 to skip the alternation check
     * @param requireAlternate whether this definition demands alternating inputs
     */
    public boolean acceptInput(int inputSeq, long now, int minIntervalTicks, int inputKind,
                               boolean requireAlternate) {
        if (inputSeq <= lastInputSeq) {
            return false;
        }
        if (lastInputTick != Long.MIN_VALUE && now - lastInputTick < Math.max(0, minIntervalTicks)) {
            return false;
        }
        if (now < cooldownUntilTick) {
            return false;
        }
        if (requireAlternate && inputKind >= 0 && inputKind == lastInputKind) {
            return false;
        }
        lastInputSeq = inputSeq;
        lastInputTick = now;
        if (inputKind >= 0) {
            lastInputKind = inputKind;
        }
        progress++;
        return true;
    }

    /** The input kind last accepted, or -1 when none has been. */
    public int lastInputKind() {
        return lastInputKind;
    }

    /** The tick this session may next accept an input on. */
    public long cooldownUntilTick() {
        return cooldownUntilTick;
    }

    /**
     * Stamps a cooldown that ends at {@code untilTick}.
     *
     * <p>An absolute deadline rather than a counter, which is the correction to the source's
     * {@code breakCooldown}: that one is set on a successful roll and never decremented anywhere, so
     * a restraint that has been strained once can never be strained again. A deadline compared against
     * the clock cannot fail to expire, because nothing has to remember to tick it.
     */
    public void stampCooldown(long untilTick) {
        if (untilTick > cooldownUntilTick) {
            cooldownUntilTick = untilTick;
        }
    }
}
