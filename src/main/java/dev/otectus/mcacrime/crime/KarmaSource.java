package dev.otectus.mcacrime.crime;

/**
 * Why a karma mutation happened. Carried through {@link dev.otectus.mcacrime.engine.CrimeState} so the
 * future anti-farm caps (spec §3.3) can distinguish passive sources (trade/gift, easily farmed) from
 * structured ones (quests, defense, crimes) without changing the mutation signature.
 *
 * <p>Only {@link #DECAY}, {@link #ADMIN}, and {@link #RECONCILE} are used in 0.1.0; the rest are
 * reserved for the crime-detection and reward phases.
 */
public enum KarmaSource {
    /** Passive ±1/day decay toward zero. */
    DECAY,
    /** An operator command or admin API call. */
    ADMIN,
    /** Login reconciliation / defensive recompute. */
    RECONCILE,
    /** Reserved (Phase 2): a committed crime. */
    CRIME,
    /** Reserved (Phase 5/7): a reward grant. */
    REWARD,
    /** Reserved (Phase 7): an MCA: Quests outcome. */
    QUEST
}
