package dev.otectus.mcacrime;

import dev.otectus.mcacrime.memory.ReportState;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * The withheld report state. Observations are stored by enum name, so the round trip is what keeps a
 * relative's silence from being read back as a pending report the next time the world loads.
 */
class ReportStateTest {

    @Test
    void withheldRoundTripsByNameAndByLabel() {
        assertEquals(ReportState.WITHHELD, ReportState.byName(ReportState.WITHHELD.name()));
        assertEquals(ReportState.WITHHELD, ReportState.byName("withheld"));
        assertEquals("withheld", ReportState.WITHHELD.lower());
        assertEquals("mcacrime.report.state.withheld", ReportState.WITHHELD.labelKey());
    }

    @Test
    void withheldIsItsOwnStateRatherThanSuppressed() {
        // Intimidation and a family choosing silence are different facts about the same villager, and
        // dialogue has to be able to tell them apart.
        assertNotEquals(ReportState.SUPPRESSED, ReportState.WITHHELD);
    }

    @Test
    void anUnknownNameStillReadsAsPending() {
        assertEquals(ReportState.PENDING, ReportState.byName("not-a-state"));
    }
}
