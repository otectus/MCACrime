package dev.otectus.mcacrime.facility;

import dev.otectus.mcacrime.facility.CareHandoverPolicy.Decision;
import dev.otectus.mcacrime.facility.CareHandoverPolicy.Outcome;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Whether a prisoner who has stopped being fit to hold is walked to a care room or left where they are.
 *
 * <h2>The asymmetry being tested</h2>
 *
 * <p>Recovering in the cell always works. Walking somewhere better is an improvement that has five ways
 * of being worse than the thing it improves on, and each of them is a case below: no destination, nobody
 * to walk them, already there, already walking, and too far. Every one of those has to fall back
 * silently to the behaviour that always works, because the prisoner in question is by definition
 * already in trouble — a handover that goes wrong is a collapsing villager being marched across a
 * field, or handed from one guard to another every ten seconds forever.
 *
 * <p>Custody recovery itself is not in question here. That decision was made by
 * {@link CustodyCarePolicy} before this is asked, and nothing below may reverse it.
 */
class CareHandoverPolicyTest {

    private static Decision decide(boolean careRoom, boolean alreadyThere, boolean escorting,
                                   boolean guard) {
        return CareHandoverPolicy.decide(true, careRoom, alreadyThere, escorting, guard, 100.0D, 0.0D);
    }

    /** Everything in place: a validated room, a free guard, and a prisoner who needs one. */
    @Test
    void aValidatedRoomAndACapableGuardStartTheWalk() {
        Decision decision = decide(true, false, false, true);
        assertSame(Outcome.ESCORT_TO_CARE_ROOM, decision.outcome());
        assertTrue(decision.handOver());
        assertFalse(decision.reason().isBlank());
    }

    /** No room assigned. The ordinary answer on every world until somebody assigns one. */
    @Test
    void noCareRoomMeansRecoverInPlace() {
        Decision decision = decide(false, false, false, true);
        assertSame(Outcome.RECOVER_IN_PLACE, decision.outcome());
        assertFalse(decision.handOver());
        assertTrue(decision.reason().contains("care room"), decision.reason());
    }

    /**
     * Nobody to walk them.
     *
     * <p>A prisoner is not released to find the care room themselves, and an escort with no guard is a
     * cuffed villager wandering off. Without somebody to hand them to, the cell is the answer.
     */
    @Test
    void noGuardMeansRecoverInPlace() {
        assertSame(Outcome.RECOVER_IN_PLACE, decide(true, false, false, false).outcome());
    }

    /**
     * The loop that would otherwise never end.
     *
     * <p>A prisoner recovering in the care room satisfies every other condition for being walked to the
     * care room. Without this, the care evaluation would hand them to a guard every interval, forever,
     * and the villager would spend their recovery being escorted in circles inside one room.
     */
    @Test
    void aPrisonerAlreadyInTheRoomIsLeftAlone() {
        Decision decision = decide(true, true, false, true);
        assertSame(Outcome.RECOVER_IN_PLACE, decision.outcome());
        assertTrue(decision.reason().contains("already"), decision.reason());
    }

    /** An escort already owns them; a second one would take custody off the guard who has it. */
    @Test
    void anEscortInProgressIsNotRestarted() {
        assertSame(Outcome.RECOVER_IN_PLACE, decide(true, false, true, true).outcome());
    }

    /** Bounded like every other automatic destination: an unfit prisoner is not marched across the map. */
    @Test
    void aDistantRoomIsNotWorthTheWalk() {
        assertSame(Outcome.ESCORT_TO_CARE_ROOM,
                CareHandoverPolicy.decide(true, true, false, false, true, 400.0D, 10_000.0D).outcome());
        assertSame(Outcome.RECOVER_IN_PLACE,
                CareHandoverPolicy.decide(true, true, false, false, true, 40_000.0D, 10_000.0D).outcome());
        // Zero means unbounded, which is what the live caller passes because the facility search has
        // already applied its own configured radius.
        assertSame(Outcome.ESCORT_TO_CARE_ROOM,
                CareHandoverPolicy.decide(true, true, false, false, true, 1e9D, 0.0D).outcome());
    }

    /** A prisoner who is not recovering is not being handed anywhere, whatever else is true. */
    @Test
    void nothingHappensToAPrisonerWhoIsNotRecovering() {
        Decision decision = CareHandoverPolicy.decide(false, true, false, false, true, 1.0D, 0.0D);
        assertSame(Outcome.RECOVER_IN_PLACE, decision.outcome());
        assertTrue(decision.reason().contains("not in custody recovery"), decision.reason());
    }

    /** A facility of the wrong role is not a care room, however it was passed in. */
    @Test
    void onlyACareRoomCounts() {
        FacilityAssignment cell = FacilityAssignment.of(
                TownsteadBuildingRef.unbound(new net.minecraft.resources.ResourceLocation("minecraft",
                        "overworld")),
                FacilityRole.JAIL_CELL, new net.minecraft.core.BlockPos(0, 64, 0), "test", 0L);
        assertSame(Outcome.RECOVER_IN_PLACE,
                CareHandoverPolicy.decide(true, cell, false, false, true, 1.0D, 0.0D).outcome());

        FacilityAssignment care = FacilityAssignment.of(
                TownsteadBuildingRef.unbound(new net.minecraft.resources.ResourceLocation("minecraft",
                        "overworld")),
                FacilityRole.CARE_ROOM, new net.minecraft.core.BlockPos(0, 64, 0), "test", 0L);
        assertSame(Outcome.ESCORT_TO_CARE_ROOM,
                CareHandoverPolicy.decide(true, care, false, false, true, 1.0D, 0.0D).outcome());

        assertSame(Outcome.RECOVER_IN_PLACE,
                CareHandoverPolicy.decide(true, null, false, false, true, 1.0D, 0.0D).outcome());
    }
}
