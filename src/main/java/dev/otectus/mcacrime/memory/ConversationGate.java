package dev.otectus.mcacrime.memory;

import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Whether a villager will talk to one player right now, given what it remembers about them (0.7.7).
 *
 * <p>The interaction handler used to refuse on fear and anger alone and never read the apology flag.
 * An accepted apology changed almost nothing for a single punch: a punch is severity 0.7, which the
 * apology rule then treated as severe, so it took three percent of the anger and none of the fear, and
 * the next click was refused with the hint telling the player to apologize. This is the rule instead:
 *
 * <ul>
 *   <li>A memory blocks at the existing thresholds: fear at least {@value #FEAR_BLOCK} or anger at
 *       least {@value #ANGER_BLOCK}.</li>
 *   <li>Any blocking memory that has not been apologized for refuses, as before.</li>
 *   <li>When every blocking memory has been apologized for, conversation resumes at once, unless one
 *       of them is grave: severity {@link VictimCrimeMemory#GRAVE_SEVERITY} or higher, or more than two
 *       repeats. A grave memory keeps the villager wary until it decays, and the player is told how
 *       long.</li>
 * </ul>
 *
 * <p>Only this offender's memories count. A memory of somebody else, however strong, is that player's
 * business; the gate filters by perpetrator itself rather than trusting every caller to.
 */
public final class ConversationGate {

    /** Fear at or above which a memory refuses conversation. Unchanged from 0.7.2. */
    public static final double FEAR_BLOCK = 0.45;
    /** Anger at or above which a memory refuses conversation. Unchanged from 0.7.2. */
    public static final double ANGER_BLOCK = 0.6;

    private ConversationGate() {
    }

    public enum Verdict {
        /** Nothing blocks, or every block was apologized for and none is grave. The click is ordinary. */
        ALLOW,
        /** A blocking memory has not been apologized for. */
        REFUSE,
        /** Every block was apologized for, but one is grave and has not faded yet. */
        WARY
    }

    /**
     * The answer, with the memory that decided it.
     *
     * @param deciding       the memory the villager speaks about: the strongest unapologized block for
     *                       {@link Verdict#REFUSE}, the longest-lasting grave block for
     *                       {@link Verdict#WARY}, and null for {@link Verdict#ALLOW}
     * @param ticksRemaining for {@link Verdict#WARY}, game ticks until the villager talks again
     */
    public record Decision(Verdict verdict, @Nullable VictimCrimeMemory deciding, long ticksRemaining) {
        public boolean allows() {
            return verdict == Verdict.ALLOW;
        }
    }

    public static boolean blocks(VictimCrimeMemory memory, long now, double multiplier) {
        return memory.fearAt(now, multiplier) >= FEAR_BLOCK || memory.angerAt(now, multiplier) >= ANGER_BLOCK;
    }

    public static boolean grave(VictimCrimeMemory memory) {
        return memory.severity() >= VictimCrimeMemory.GRAVE_SEVERITY || memory.repeatCount() > 2;
    }

    public static Decision decide(@Nullable UUID offender, @Nullable Collection<VictimCrimeMemory> memories,
                                  long now, double multiplier) {
        if (offender == null || memories == null || memories.isEmpty()) {
            return new Decision(Verdict.ALLOW, null, 0L);
        }
        List<VictimCrimeMemory> blocking = memories.stream()
                .filter(memory -> memory != null && offender.equals(memory.perpetrator()))
                .filter(memory -> blocks(memory, now, multiplier))
                .toList();
        if (blocking.isEmpty()) {
            return new Decision(Verdict.ALLOW, null, 0L);
        }
        Comparator<VictimCrimeMemory> strength = Comparator.comparingDouble(
                memory -> memory.fearAt(now, multiplier) + memory.angerAt(now, multiplier));
        VictimCrimeMemory unapologized = blocking.stream().filter(memory -> !memory.apologized())
                .max(strength).orElse(null);
        if (unapologized != null) {
            return new Decision(Verdict.REFUSE, unapologized, 0L);
        }
        VictimCrimeMemory wary = null;
        long remaining = 0L;
        for (VictimCrimeMemory memory : blocking) {
            if (!grave(memory)) {
                continue;
            }
            long ticks = memory.ticksUntilCalm(now, multiplier, FEAR_BLOCK, ANGER_BLOCK);
            if (wary == null || ticks > remaining) {
                wary = memory;
                remaining = ticks;
            }
        }
        return wary == null ? new Decision(Verdict.ALLOW, null, 0L) : new Decision(Verdict.WARY, wary, remaining);
    }

    /** Whole in-game days, rounded up and never zero, for "give them about N more days". */
    public static long daysRemaining(long ticks) {
        if (ticks <= 0L) {
            return 1L;
        }
        if (ticks == Long.MAX_VALUE) {
            return Long.MAX_VALUE;
        }
        return Math.max(1L, (ticks + 23_999L) / 24_000L);
    }
}
