package dev.otectus.mcacrime.activity;

import javax.annotation.Nullable;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Which ordinary villager behaviours have to stand aside for each kind of MCA: Crime activity.
 *
 * <p>A pure table and nothing else — no entity, no server, no compat layer, no config. That is what
 * makes the coordination rule reviewable: every row can be read in one place and pinned by one
 * assertion, instead of being spread across nine producers as an ad-hoc "and also stop the villager
 * doing X" at each call site.
 *
 * <h2>How to read a row</h2>
 *
 * <p>{@link #yields(CrimeActivityView.Kind, CrimeActivityOperation)} answers "must this behaviour
 * stand aside?", and {@link #allowed(CrimeActivityView.Kind)} is the complement that a
 * {@link CrimeActivityView} carries with it. Three behaviours yield for every kind —
 * {@link CrimeActivityOperation#WORK_START}, {@link CrimeActivityOperation#REST_TRAVEL} and
 * {@link CrimeActivityOperation#SOCIAL_WANDER} — because a villager who wanders off to a workbench,
 * to bed or into a gossip circle mid-arrest is the same bug three times. The table earns its keep on
 * the other two:
 *
 * <ul>
 *   <li>{@link CrimeActivityOperation#REACTION_LOCK} freezes the villager in place. That is fine
 *       while MCA: Crime is holding them still anyway ({@code HOLD}, {@code CHALLENGE},
 *       {@code CUSTODY}) and ruinous while it is walking them somewhere.</li>
 *   <li>{@link CrimeActivityOperation#DISPLAY_TOOL} puts a borrowed item in their hand. That matters
 *       only where MCA: Crime needs hand state to mean something — restraints, an arrest, an escort
 *       and the death-loot rule downstream of them.</li>
 * </ul>
 *
 * <p>Nothing here claims the yield is <em>enforced</em>. Enforcement is the producers' and the
 * behaviour gates' job; this table only says what the answer should be.
 */
public final class OperationPolicy {

    /** Yielded by every kind: these three are how a villager wanders out of an enforcement action. */
    private static final Set<CrimeActivityOperation> ALWAYS_YIELDS = Set.of(
            CrimeActivityOperation.WORK_START,
            CrimeActivityOperation.REST_TRAVEL,
            CrimeActivityOperation.SOCIAL_WANDER);

    private static final Map<CrimeActivityView.Kind, Set<CrimeActivityOperation>> YIELDS =
            new EnumMap<>(CrimeActivityView.Kind.class);

    private static final Map<CrimeActivityView.Kind, Set<CrimeActivityOperation>> ALLOWED =
            new EnumMap<>(CrimeActivityView.Kind.class);

    static {
        // Standing as law: still, talking, hands are their own. A reaction lock costs nothing here.
        row(CrimeActivityView.Kind.HOLD);
        row(CrimeActivityView.Kind.CHALLENGE);

        // Walking somebody to a cell, or being walked: movement and hand state are both load-bearing.
        row(CrimeActivityView.Kind.ESCORT,
                CrimeActivityOperation.REACTION_LOCK, CrimeActivityOperation.DISPLAY_TOOL);
        row(CrimeActivityView.Kind.ARREST,
                CrimeActivityOperation.REACTION_LOCK, CrimeActivityOperation.DISPLAY_TOOL);

        // Held. They are not going anywhere, so a reaction may play; what must not happen is a
        // borrowed tool appearing in a prisoner's hand.
        row(CrimeActivityView.Kind.CUSTODY, CrimeActivityOperation.DISPLAY_TOOL);

        // Moving under MCA: Crime's own navigation.
        row(CrimeActivityView.Kind.PURSUIT, CrimeActivityOperation.REACTION_LOCK);
        row(CrimeActivityView.Kind.REACTION, CrimeActivityOperation.REACTION_LOCK);
        row(CrimeActivityView.Kind.THIEF_ACTION, CrimeActivityOperation.REACTION_LOCK);
        row(CrimeActivityView.Kind.MUGGING, CrimeActivityOperation.REACTION_LOCK);
    }

    private OperationPolicy() {
    }

    private static void row(CrimeActivityView.Kind kind, CrimeActivityOperation... alsoYields) {
        EnumSet<CrimeActivityOperation> yields = EnumSet.copyOf(ALWAYS_YIELDS);
        yields.addAll(Set.of(alsoYields));
        EnumSet<CrimeActivityOperation> allowed = EnumSet.allOf(CrimeActivityOperation.class);
        allowed.removeAll(yields);
        YIELDS.put(kind, Set.copyOf(yields));
        ALLOWED.put(kind, Set.copyOf(allowed));
    }

    /** Whether {@code operation} must stand aside while an activity of this kind is live. */
    public static boolean yields(@Nullable CrimeActivityView.Kind kind,
                                 @Nullable CrimeActivityOperation operation) {
        if (kind == null || operation == null) {
            return false;
        }
        return YIELDS.getOrDefault(kind, Set.of()).contains(operation);
    }

    /** Everything that must stand aside for this kind. */
    public static Set<CrimeActivityOperation> yielded(@Nullable CrimeActivityView.Kind kind) {
        return kind == null ? Set.of() : YIELDS.getOrDefault(kind, Set.of());
    }

    /** Everything that may still happen, which is what a claim carries with it. */
    public static Set<CrimeActivityOperation> allowed(@Nullable CrimeActivityView.Kind kind) {
        return kind == null ? Set.copyOf(EnumSet.allOf(CrimeActivityOperation.class))
                : ALLOWED.getOrDefault(kind, Set.of());
    }
}
