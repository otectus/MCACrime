package dev.otectus.mcacrime.enforcement;

import dev.otectus.mcacrime.facility.FacilityRole;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Walking a condemned prisoner to a device, and every way that walk ends (0.7.5 §3.19, M6.7).
 *
 * <p>The live walk needs a level, a guard and a prisoner; what is asserted here is everything that
 * decides its behaviour and can be decided without one — the ceremony arithmetic, the bookkeeping
 * that guarantees a failed escort frees its claim, and the role rules that keep an execution site
 * from becoming somewhere an arrest can be routed to.
 *
 * <p>The load-bearing case is {@link #noGuillotineKeepsCaptiveInCustody()}: with no assigned site the
 * service refuses to begin, and refusing to begin is the whole of §3.19's "with no usable guillotine
 * the condemned simply stays in custody". There is no substitute death anywhere in the class, which
 * is why the test asserts the outcome value rather than an absence.
 */
class CondemnedEscortTest {

    @BeforeEach
    @AfterEach
    void clear() {
        CondemnedEscortService.clearAll();
        ExecutionSiteRegistry.clear();
    }

    // ------------------------------------------------------------------ the ceremony

    /**
     * The blade has to land <em>inside</em> the window, not at the end of it.
     *
     * <p>Dropping it at {@code armedAt + delay} would land it after the authorisation expired, the
     * device would refuse, and every guard-carried execution would quietly do nothing at all.
     */
    @Test
    void theBladeIsDroppedInsideTheWindow() {
        long armed = 1000L;
        int window = 1200;
        int blade = 5;

        long drop = CondemnedEscortService.dropAt(armed, window, blade);

        assertTrue(drop > armed, "the ceremony window is not skipped");
        assertTrue(drop + blade < armed + window,
                "the blow must land before the authorisation expires");
    }

    @Test
    void aShortWindowStillLeavesTheCeremonyAtLeastOneTick() {
        assertEquals(1001L, CondemnedEscortService.dropAt(1000L, 1, 5));
        assertEquals(1001L, CondemnedEscortService.dropAt(1000L, 0, 0));
    }

    @Test
    void aLongBladeDelayNeverPushesTheDropPastTheWindow() {
        long armed = 500L;
        for (int window : new int[] {20, 100, 1200, 72000}) {
            for (int blade : new int[] {1, 5, 40, 200}) {
                long drop = CondemnedEscortService.dropAt(armed, window, blade);
                assertTrue(drop >= armed + 1L, "the drop never precedes the order");
                assertTrue(drop <= armed + Math.max(1, window),
                        "window " + window + " with blade " + blade + " overshot");
            }
        }
    }

    @Test
    void theEscortTimeoutDefaultsToTheDocumentedFigure() {
        assertEquals(2400, CondemnedEscortService.timeoutTicks(),
                "with no config loaded the documented default is what the walk uses");
    }

    // ------------------------------------------------------------------ bookkeeping

    @Test
    void noGuillotineKeepsCaptiveInCustody() {
        // No level, no site, no device: begin refuses and nothing at all is recorded. The prisoner is
        // not released, not despawned and not killed -- they are simply still in their cell.
        assertEquals(CondemnedEscortService.Outcome.NOT_PERMITTED,
                CondemnedEscortService.begin(null, null, null));
        assertEquals(0, CondemnedEscortService.activeCount());
    }

    @Test
    void cancellingAnEscortNobodyIsRunningChangesNothing() {
        assertFalse(CondemnedEscortService.cancel(null, UUID.randomUUID(), "nothing to cancel"));
        assertEquals(0, CondemnedEscortService.cancelByGuard(null, UUID.randomUUID(), "no escorts"));
        assertEquals(0, CondemnedEscortService.cancelByGuard(null, null, "no guard"));
    }

    @Test
    void nobodyIsBeingEscortedUntilOneBegins() {
        assertFalse(CondemnedEscortService.escorting(UUID.randomUUID()));
        assertFalse(CondemnedEscortService.escorting(null));
        assertFalse(CondemnedEscortService.ceremonyRunning(UUID.randomUUID()));
    }

    @Test
    void everyOutcomeIsDistinctAndNamed() {
        assertEquals(7, CondemnedEscortService.Outcome.values().length,
                "a new way for the walk to fail needs its own value, not a shared one");
        for (CondemnedEscortService.Outcome outcome : CondemnedEscortService.Outcome.values()) {
            assertFalse(outcome.name().isBlank());
        }
    }

    /**
     * The timeout has a return step, and it never invents a custody to return anybody to.
     *
     * <p>§3.19 says the guard "gives up and returns them to a cell". Until M7 the walk simply stopped:
     * the claim and the site reservation were released and the prisoner was left standing wherever the
     * deadline caught them, which reads in-world as a condemned villager loose in the village with a
     * sentence nobody is serving. {@code returnToCell} is that missing step, and what is asserted here
     * is its refusal half -- with no server, no custody record and no guard there is nothing to return,
     * and the honest answer is {@code false} rather than a fabricated cell.
     */
    @Test
    void thereIsNothingToReturnWhenNobodyIsInCustody() {
        assertFalse(CondemnedEscortService.returnToCell(null, UUID.randomUUID(), UUID.randomUUID()),
                "no server means no custody record, and no custody record means no cell to walk back to");
        assertFalse(CondemnedEscortService.returnToCell(null, null, null));
    }

    // ------------------------------------------------------------------ the site

    @Test
    void anExecutionSiteIsReservableAndIsNotACell() {
        assertTrue(FacilityRole.EXECUTION_SITE.reservable(),
                "one condemned prisoner at a time, held by the same reservation machinery as a cell");
        assertFalse(FacilityRole.EXECUTION_SITE.holdsPrisoners(),
                "an arrest must never be routed to an execution site");
        assertEquals(1, FacilityRole.EXECUTION_SITE.defaultCapacity());
        assertEquals("execution_site", FacilityRole.EXECUTION_SITE.id());
        assertEquals(FacilityRole.EXECUTION_SITE, FacilityRole.parse("execution_site").orElseThrow());
    }

    @Test
    void aCellIsStillTheOnlyRoleAnArrestMayTarget() {
        for (FacilityRole role : FacilityRole.values()) {
            if (role == FacilityRole.JAIL_CELL) {
                assertTrue(role.holdsPrisoners());
            } else {
                assertFalse(role.holdsPrisoners(), role + " must not accept an arrest");
            }
        }
    }

    @Test
    void theDeviceIndexStartsEmptyAndForgetsCleanly() {
        assertEquals(0, ExecutionSiteRegistry.size());
        ExecutionSiteRegistry.remember(null, null);
        assertEquals(0, ExecutionSiteRegistry.size(), "a null level remembers nothing");
        assertFalse(ExecutionSiteRegistry.standing(null, null));
    }

    @Test
    void theSiteSearchRadiusDefaultsToTheDocumentedFigure() {
        assertEquals(48, ExecutionSiteRegistry.searchRadius());
        assertEquals(12, ExecutionSiteRegistry.SITE_DEVICE_RADIUS,
                "the device look is inside the assigned building, not around the village");
    }
}
