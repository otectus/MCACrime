package dev.otectus.mcacrime.relationship;

import java.util.function.BooleanSupplier;

/**
 * What MCA's own heart penalties are allowed to cost a player (0.7.7).
 *
 * <p>MCA charges a killing natively: every MCA villager within 32 blocks of the victim, and every
 * loaded relative wherever they are, loses 20 hearts toward a player killer, with no sight check. That
 * is the "unjustified murder hearts" report: a neighbour behind a wall and a relative across the map
 * lose as much as somebody who watched. The native justice hooks route each of those charges through
 * {@link #tragedyDelta}, one call per mourner, in this order:
 *
 * <ol>
 *   <li>An exempt Thief fight costs nothing (unchanged since 0.7.5).</li>
 *   <li>{@code relationship.murderHeartPenalty=false} costs nothing.</li>
 *   <li>{@code relationship.murderHeartsRequireWitness=false} restores MCA's native value.</li>
 *   <li>A masked killer whose mask hides identity costs nothing: no mourner can name them.</li>
 *   <li>A mourner who did not identify the killer loses nothing.</li>
 *   <li>Otherwise MCA's value stands.</li>
 * </ol>
 *
 * <p>Only a negative, player-attributed delta is ever changed. The identity questions arrive as
 * suppliers because answering them costs line-of-sight traces, and the order above usually decides
 * before they are needed.
 *
 * <p>Community standing is not touched by any of this: hearts are one villager's feelings, standing is
 * the community's ledger, and the two have independent controls.
 */
public final class NativeHeartPolicy {

    private NativeHeartPolicy() {
    }

    /**
     * MCA's per-mourner killing penalty after Crime's rules.
     *
     * @param nativeDelta       MCA's value, -20 on every probed build
     * @param playerKiller      the damage source's responsible entity is a player
     * @param thiefExempt       the kill was lawful force against a Crime thief
     * @param penaltyEnabled    {@code relationship.murderHeartPenalty}
     * @param requireWitness    {@code relationship.murderHeartsRequireWitness}
     * @param maskedHidden      the killer wore a mask that hides identity from witnesses
     * @param mournerIdentified this mourner perceived the killer well enough to name them
     */
    public static int tragedyDelta(int nativeDelta, boolean playerKiller, boolean thiefExempt,
                                   boolean penaltyEnabled, boolean requireWitness,
                                   BooleanSupplier maskedHidden, BooleanSupplier mournerIdentified) {
        if (nativeDelta >= 0 || !playerKiller) {
            return nativeDelta;
        }
        if (thiefExempt || !penaltyEnabled) {
            return 0;
        }
        if (!requireWitness) {
            return nativeDelta;
        }
        if (maskedHidden != null && maskedHidden.getAsBoolean()) {
            return 0;
        }
        return mournerIdentified != null && mournerIdentified.getAsBoolean() ? nativeDelta : 0;
    }

    /**
     * MCA's own hurt penalty, the hearts a villager loses toward a player who hits it.
     *
     * @param withheld the hit may yet be forgiven as minor friendly fire; the hearts are held back and
     *                 re-applied when the hit is charged after all
     */
    public static int hurtDelta(int nativeDelta, boolean playerAttacker, boolean thiefExempt,
                                boolean maskedHidden, boolean withheld) {
        if (nativeDelta >= 0) {
            return nativeDelta;
        }
        if (thiefExempt) {
            return 0;
        }
        if (!playerAttacker) {
            return nativeDelta;
        }
        return maskedHidden || withheld ? 0 : nativeDelta;
    }

    /**
     * Whether MCA: Crime's own personal-heart consequences apply to a recorded crime at all.
     *
     * <p>A masked offender whose mask hides identity is a stranger to every villager: there is nobody to
     * feel anything toward, so the victim, the family and the witnesses all keep their hearts. The
     * record still exists, and the crime still costs community standing and Heat by its own rules.
     */
    public static boolean crimeHeartsApply(boolean masked, boolean maskHidesIdentity) {
        return !(masked && maskHidesIdentity);
    }

    /**
     * Whether MCA: Crime's family and witness loss for a killing applies to one mourner.
     *
     * @param killing       the crime is a killing
     * @param penaltyEnabled {@code relationship.murderHeartPenalty}
     * @param nativeHooks   MCA's own killing penalty is gated by the native hooks. Without them MCA charges
     *                      everybody nearby natively, and Crime must not stack its own loss on top
     * @param chargedByMca  MCA already charged this mourner for this killing
     */
    public static boolean crimeKillingLossApplies(boolean killing, boolean penaltyEnabled, boolean nativeHooks,
                                                  boolean chargedByMca) {
        if (!killing) {
            return true;
        }
        return penaltyEnabled && nativeHooks && !chargedByMca;
    }
}
