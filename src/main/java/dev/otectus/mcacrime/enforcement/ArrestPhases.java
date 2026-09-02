package dev.otectus.mcacrime.enforcement;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * The gate table and the legal-edge table for {@link ArrestPhase}. Pure, with no Minecraft or config
 * dependency, so the whole lifecycle can be asserted in a unit test.
 *
 * <p>This is where "avoid scattering independent booleans across unrelated systems where contradictory
 * combinations can occur" is actually enforced. Every question the rest of the mod used to answer from
 * its own private flag — may a challenge open, is the player restrained, is the escort running, is a
 * sentence under way, may a guard use force — is answered here from one value, so a contradictory
 * combination is not merely unlikely but unrepresentable.
 */
public final class ArrestPhases {

    /**
     * Phases in which an arrest is under way.
     *
     * <p>Everything from the moment the player answers a challenge to the moment the sentence ends. A
     * player in any of these is the law's business already, and a guard who challenges, targets, or
     * swings at them is interrupting a process rather than starting one.
     */
    private static final Set<ArrestPhase> IN_PROGRESS = EnumSet.of(
            ArrestPhase.SURRENDERED, ArrestPhase.RESTRAINED, ArrestPhase.ESCORTING, ArrestPhase.JAILED);

    /** Phases in which the player is physically restrained: cuffed, tethered, and movement-limited. */
    private static final Set<ArrestPhase> RESTRAINED = EnumSet.of(
            ArrestPhase.SURRENDERED, ArrestPhase.RESTRAINED, ArrestPhase.ESCORTING);

    private static final Map<ArrestPhase, Set<ArrestPhase>> EDGES = new EnumMap<>(ArrestPhase.class);

    static {
        // Every phase may drop to NONE (a stand-down, a pardon, the basis evaporating) or to RECOVERY
        // (the arrest failed). Those two are always legal and are not repeated per row below.
        EDGES.put(ArrestPhase.NONE, EnumSet.of(ArrestPhase.CONFRONTED, ArrestPhase.SURRENDERED,
                ArrestPhase.JAILED));
        EDGES.put(ArrestPhase.CONFRONTED, EnumSet.of(ArrestPhase.SURRENDERED, ArrestPhase.JAILED));
        EDGES.put(ArrestPhase.SURRENDERED, EnumSet.of(ArrestPhase.RESTRAINED, ArrestPhase.JAILED));
        EDGES.put(ArrestPhase.RESTRAINED, EnumSet.of(ArrestPhase.ESCORTING, ArrestPhase.JAILED));
        EDGES.put(ArrestPhase.ESCORTING, EnumSet.of(ArrestPhase.RESTRAINED, ArrestPhase.JAILED));
        EDGES.put(ArrestPhase.JAILED, EnumSet.noneOf(ArrestPhase.class));
        EDGES.put(ArrestPhase.RECOVERY, EnumSet.noneOf(ArrestPhase.class));
    }

    private ArrestPhases() {
    }

    /**
     * Whether a guard may open a confrontation screen against a player in this phase.
     *
     * <p>{@code NONE} only. This single predicate is what stops the screen reopening every scan for a
     * player who is mid-escort — a case the old code could not see at all, because Heat and charges
     * both persist through an arrest, so the player stays a Legal Target for the whole walk.
     */
    public static boolean canOpenChallenge(ArrestPhase phase) {
        return phase == ArrestPhase.NONE;
    }

    /** Whether the player is physically restrained: cuffed, tethered, movement-limited. */
    public static boolean isRestrained(ArrestPhase phase) {
        return RESTRAINED.contains(phase);
    }

    /** Whether the escort should be walking this player somewhere. */
    public static boolean escortRuns(ArrestPhase phase) {
        return phase == ArrestPhase.ESCORTING;
    }

    /** Whether a sentence is counting down. The authority remains {@code JailState}; this gates it. */
    public static boolean sentenceRunning(ArrestPhase phase) {
        return phase == ArrestPhase.JAILED;
    }

    /** Whether an arrest is under way, from the answer to the challenge to the end of the sentence. */
    public static boolean inProgress(ArrestPhase phase) {
        return IN_PROGRESS.contains(phase);
    }

    /**
     * Whether a guard may use force against a player in this phase, before the resisting-arrest
     * projection is consulted.
     *
     * <p>Never during an arrest. Swinging at somebody already in custody is the visible form of the
     * bug this table exists to prevent.
     */
    public static boolean forcePermitted(ArrestPhase phase, boolean challengesEnabled, boolean resisting) {
        if (inProgress(phase) || phase == ArrestPhase.CONFRONTED) {
            return false;
        }
        // RECOVERY deliberately falls through rather than blocking force. It suppresses the screen, not
        // the law: a player who refused a challenge and is resisting stays a lawful target through a
        // failed arrest, or surrendering to a guard with nowhere to put you would be a way of buying
        // temporary immunity from one.
        return challengesEnabled ? resisting : true;
    }

    /** Whether the arrest owns a guard in this phase, so a second guard cannot take over casually. */
    public static boolean hasOwningGuard(ArrestPhase phase) {
        return phase == ArrestPhase.CONFRONTED || isRestrained(phase);
    }

    /**
     * Whether {@code from → to} is a legal transition.
     *
     * <p>An illegal edge is a no-op rather than an exception (see {@code ArrestStates.transition}),
     * which turns "contradictory combinations" from a class of bug into a debug log line.
     */
    public static boolean allows(ArrestPhase from, ArrestPhase to) {
        if (from == null || to == null) {
            return false;
        }
        if (from == to) {
            return true; // idempotent re-entry, so a replayed packet settles nothing twice
        }
        if (to == ArrestPhase.NONE || to == ArrestPhase.RECOVERY) {
            return true; // standing down and failing are always available
        }
        return EDGES.getOrDefault(from, EnumSet.noneOf(ArrestPhase.class)).contains(to);
    }
}
