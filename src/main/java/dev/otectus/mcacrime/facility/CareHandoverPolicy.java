package dev.otectus.mcacrime.facility;

import org.jetbrains.annotations.Nullable;

/**
 * Whether a prisoner who has stopped being fit to hold should be walked to a care room, or recovered
 * where they are.
 *
 * <h2>Why this is a decision and not a reflex</h2>
 *
 * <p>{@link CustodyCarePolicy} has already answered the question that matters — a prisoner whose needs
 * have collapsed is no longer being held, because a lawful sentence may not be the thing that kills
 * somebody. What is left is presentation and logistics: recovery in the cell is correct and works
 * everywhere, and a settlement that has taken the trouble to designate a {@link FacilityRole#CARE_ROOM}
 * has said where it would rather that happened (reference §8.6 step 2).
 *
 * <p>The reason this is a separate, pure decision rather than an {@code if} inside the custody tick is
 * that every one of its inputs is a way for the transfer to be the wrong thing to do, and each of them
 * has to be stated rather than discovered:
 *
 * <ul>
 *   <li><b>No care room.</b> The ordinary case. Recover in place; nothing is lost.</li>
 *   <li><b>Nobody to walk them.</b> A prisoner is not released to find the care room themselves, and a
 *       guardless escort is an escort that ends with a villager wandering off with the cuffs on. Without
 *       a capable guard the answer is the cell.</li>
 *   <li><b>Already there.</b> A prisoner recovering <em>in</em> the care room must not be handed to a
 *       guard to be walked to the care room, every care interval, forever.</li>
 *   <li><b>Already walking.</b> The escort machinery owns the prisoner once it has them; starting a
 *       second escort would take custody off the guard who has it.</li>
 *   <li><b>Too far.</b> Bounded like every other automatic destination in this mod: the point of the
 *       radius is that an unfit prisoner is not marched across the map, which is worse for them than
 *       the cell they are already in.</li>
 * </ul>
 *
 * <p>Nothing here touches a level, an entity or the world data, which is what lets the rule be asserted
 * directly instead of inferred from an escort that did or did not start.
 */
public final class CareHandoverPolicy {

    private CareHandoverPolicy() {
    }

    /** What custody should do with a prisoner who has just entered recovery. */
    public enum Outcome {

        /** Recover in the cell, exactly as custody did before care rooms existed. */
        RECOVER_IN_PLACE,

        /** Hand the prisoner to a guard and walk them to the assigned care room. */
        ESCORT_TO_CARE_ROOM
    }

    /**
     * The decision, with the sentence an operator reads in the custody log.
     *
     * @param outcome what to do
     * @param reason  why; never empty
     */
    public record Decision(Outcome outcome, String reason) {

        public Decision {
            reason = reason == null || reason.isBlank() ? outcome.name() : reason;
        }

        /** Whether an escort should be started for this prisoner. */
        public boolean handOver() {
            return outcome == Outcome.ESCORT_TO_CARE_ROOM;
        }
    }

    private static final Decision IN_PLACE_NO_ROOM =
            new Decision(Outcome.RECOVER_IN_PLACE, "no care room is assigned within range");

    /**
     * Decides for one recovering prisoner.
     *
     * <p>Order is the policy, and the first three questions are all cheaper than the last two on
     * purpose: a world with no care room assigned — which is every world until an operator assigns one
     * — answers on the first line and never asks anything else.
     *
     * @param inRecovery       whether the prisoner is actually in custody recovery right now
     * @param careRoomFound    whether a validated, usable care room was selected
     * @param alreadyInCareRoom whether the prisoner is already being held at that care room
     * @param escortInProgress whether an escort already owns this prisoner
     * @param guardAvailable   whether a capable guard is on hand to walk them
     * @param distanceSqr      squared distance from the prisoner to the care room anchor
     * @param maxDistanceSqr   the squared ceiling; zero or less means unbounded
     */
    public static Decision decide(boolean inRecovery, boolean careRoomFound, boolean alreadyInCareRoom,
                                  boolean escortInProgress, boolean guardAvailable,
                                  double distanceSqr, double maxDistanceSqr) {
        if (!inRecovery) {
            return new Decision(Outcome.RECOVER_IN_PLACE, "the prisoner is not in custody recovery");
        }
        if (!careRoomFound) {
            return IN_PLACE_NO_ROOM;
        }
        if (alreadyInCareRoom) {
            return new Decision(Outcome.RECOVER_IN_PLACE, "the prisoner is already in the care room");
        }
        if (escortInProgress) {
            return new Decision(Outcome.RECOVER_IN_PLACE, "an escort already has this prisoner");
        }
        if (!guardAvailable) {
            return new Decision(Outcome.RECOVER_IN_PLACE,
                    "no guard is available to walk the prisoner to the care room");
        }
        if (maxDistanceSqr > 0.0D && distanceSqr > maxDistanceSqr) {
            return new Decision(Outcome.RECOVER_IN_PLACE,
                    "the care room is further away than an unfit prisoner should be walked");
        }
        return new Decision(Outcome.ESCORT_TO_CARE_ROOM,
                "walking the prisoner to the assigned care room to recover there");
    }

    /** The same question against an already-selected assignment, for the one caller that has one. */
    public static Decision decide(boolean inRecovery, @Nullable FacilityAssignment careRoom,
                                  boolean alreadyInCareRoom, boolean escortInProgress,
                                  boolean guardAvailable, double distanceSqr, double maxDistanceSqr) {
        return decide(inRecovery, careRoom != null && careRoom.role() == FacilityRole.CARE_ROOM,
                alreadyInCareRoom, escortInProgress, guardAvailable, distanceSqr, maxDistanceSqr);
    }
}
