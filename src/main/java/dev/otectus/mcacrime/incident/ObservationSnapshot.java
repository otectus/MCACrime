package dev.otectus.mcacrime.incident;

import dev.otectus.mcacrime.detect.WitnessResult;

/**
 * What was true when an act began, carried forward to the moment it is committed (0.7.2 §14.3).
 *
 * <p>Almost every incident in this mod is committed in the same instant it happens, so re-scanning
 * for witnesses and reading the offender's worn mask at commit time is exactly right. A thrown
 * weapon breaks that: the throw and the impact are separated by up to three seconds of flight, and in
 * between the thrower can change mask, walk out of the room, or both.
 *
 * <p>So the launch is snapshotted and the snapshot is what the commit reads. A witness who identified
 * the thrower keeps that identification; a bystander who only saw the bottle land does not inherit the
 * projectile's hidden owner UUID; and swapping masks mid-flight cannot retroactively rewrite an
 * observation of the throw (SAND-14, SAND-15).
 *
 * @param witnesses  the witness scan taken at launch, used instead of a fresh scan at commit
 * @param masked     whether the offender was validly masked when the act began
 * @param observedAt the game tick the act began, which is what the stored observations are dated with
 */
public record ObservationSnapshot(WitnessResult witnesses, boolean masked, long observedAt) {
}
