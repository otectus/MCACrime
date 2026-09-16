package dev.otectus.mcacrime.job;

/**
 * Whether one NPC may be recruited as, or act as, this mod's criminal (0.7.2, spec §3).
 *
 * <p>Pure, like {@link CriminalJobAssigner} and for the same reason: the question is asked from six
 * different places — the assignment sweep, the public job writer, thief tracking, forced targets, the
 * mugging session's start/continue/commit checks and guard promotion — and six copies of a role test
 * is exactly how they drifted apart. The facts arrive as plain values, so the ordering that matters
 * can be pinned by a test without a server.
 *
 * <p>Two orderings are deliberate.
 *
 * <p>First, {@link Facts#responder()} is examined before every temporary condition. Responder
 * <em>identity</em> is what excludes somebody from crime; whether they are awake, armed or on shift is
 * a different question, asked by {@code EntitySelectors.isAvailableResponder} for a different purpose.
 * A sleeping guard is still law.
 *
 * <p>Second, an unreadable classification is its own answer rather than a negative one. When MCA's
 * bindings are unavailable this reports {@link NpcMuggerEligibilityReason#CLASSIFICATION_UNAVAILABLE}
 * and fails closed, because "MCA could not be asked whether this is a guard" must never be spent as
 * "this is not a guard".
 *
 * <p>Notably absent: {@code EntitySelectors.isProtected}. That predicate treats every MCA villager as
 * a legally protected victim, which is correct for victims and would exclude the entire population
 * from ever becoming a criminal.
 */
public final class NpcMuggerEligibility {

    /**
     * Which question is being asked, because the answer differs in one respect.
     *
     * <p>An {@link #ASSIGNMENT} candidate is not a thief yet — that is the point of assigning one —
     * while an {@link #EXECUTION} actor must already hold the record it is acting on. Everything else
     * is shared, which is the whole reason this exists.
     */
    public enum Context {
        /** "May this villager be given a criminal job?" */
        ASSIGNMENT,
        /** "May this villager track, approach, threaten or debit somebody right now?" */
        EXECUTION
    }

    /**
     * Everything the decision needs, and no entity reference.
     *
     * @param loaded            the entity exists, is alive and has not been removed
     * @param mcaVillager       MCA recognises it as one of its human villagers
     * @param classifiable      MCA's role classification could actually be read; false means unknown,
     *                          never "no"
     * @param responder         law identity under {@code EntitySelectors.isResponder} — guard, archer,
     *                          or a configured responder entity
     * @param adult             strictly an adult MCA villager; an unreadable age is not
     * @param thiefRecord       world data records this villager as a thief
     * @param anyCriminalRecord world data records any criminal job at all, thief or fence
     */
    public record Facts(boolean loaded, boolean mcaVillager, boolean classifiable, boolean responder,
                        boolean adult, boolean thiefRecord, boolean anyCriminalRecord) {

        /** The honest answer for a villager nobody can reach: unknown, and therefore not eligible. */
        public static Facts unloaded(boolean thiefRecord, boolean anyCriminalRecord) {
            return new Facts(false, false, false, false, false, thiefRecord, anyCriminalRecord);
        }
    }

    /** The decision, with the reason attached so an operator is told why rather than merely "no". */
    public record Result(boolean eligible, NpcMuggerEligibilityReason reason) {

        public boolean rejected() {
            return !eligible;
        }
    }

    private static final Result OK = new Result(true, NpcMuggerEligibilityReason.ELIGIBLE);

    private NpcMuggerEligibility() {
    }

    public static Result evaluate(Facts facts, Context context) {
        if (facts == null || context == null) {
            return reject(NpcMuggerEligibilityReason.CLASSIFICATION_UNAVAILABLE);
        }
        // Identity first, and before anything that can change back in a minute.
        if (facts.responder()) {
            return reject(NpcMuggerEligibilityReason.RESPONDER);
        }
        if (!facts.loaded()) {
            return reject(NpcMuggerEligibilityReason.NOT_LOADED);
        }
        if (!facts.classifiable()) {
            return reject(NpcMuggerEligibilityReason.CLASSIFICATION_UNAVAILABLE);
        }
        if (!facts.mcaVillager()) {
            return reject(NpcMuggerEligibilityReason.NOT_A_VILLAGER);
        }
        if (context == Context.ASSIGNMENT) {
            if (!facts.adult()) {
                return reject(NpcMuggerEligibilityReason.NOT_ADULT);
            }
        } else if (!facts.thiefRecord()) {
            return reject(NpcMuggerEligibilityReason.NOT_A_THIEF);
        }
        return OK;
    }

    /**
     * A villager who is law and is <em>also</em> carrying a criminal record — the stale-role defect
     * itself, whichever way round it happened.
     *
     * <p>The sweep used to skip every existing criminal outright, so a record written before a
     * villager was promoted survived forever and drove a guard into muggings. This is the signal to
     * reconcile it instead of walking past it.
     */
    public static boolean contradictory(Facts facts) {
        return facts != null && facts.responder() && facts.anyCriminalRecord();
    }

    /**
     * Whether a criminal record forbids promoting this villager to guard.
     *
     * <p>The other half of the same invariant: excluding criminals from crime is useless if the guard
     * pass can turn a thief into a guard the following tick.
     */
    public static boolean guardPromotionBlocked(boolean anyCriminalRecord) {
        return anyCriminalRecord;
    }

    private static Result reject(NpcMuggerEligibilityReason reason) {
        return new Result(false, reason);
    }
}
