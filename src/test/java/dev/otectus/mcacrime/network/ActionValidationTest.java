package dev.otectus.mcacrime.network;

import dev.otectus.mcacrime.captivity.CustodyOwner;
import dev.otectus.mcacrime.captivity.CustodyRecord;
import dev.otectus.mcacrime.restraint.PhysicalRestraintState;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The shared action checks: range, dimension, freshness, revision and target identity.
 *
 * <p>Only the parts that can be decided without a live server are asserted here, which is most of
 * them by design — the arithmetic and the identity comparisons are exactly the parts that used to be
 * written out by hand in each packet, and the parts a mistake in is invisible until somebody exploits
 * it.
 */
class ActionValidationTest {

    private static final ResourceLocation OVERWORLD = new ResourceLocation("minecraft", "overworld");
    private static final ResourceLocation NETHER = new ResourceLocation("minecraft", "the_nether");

    @Test
    void rangeIsComparedSquaredAndRejectsNonFiniteInput() {
        assertTrue(ActionValidation.withinRangeSquared(0.0D, 5.0D));
        assertTrue(ActionValidation.withinRangeSquared(25.0D, 5.0D), "exactly at reach is in reach");
        assertFalse(ActionValidation.withinRangeSquared(25.1D, 5.0D));

        assertFalse(ActionValidation.withinRangeSquared(Double.NaN, 5.0D),
                "NaN compares false against everything, which would read as 'in range' if inverted");
        assertFalse(ActionValidation.withinRangeSquared(Double.POSITIVE_INFINITY, 5.0D));
        assertFalse(ActionValidation.withinRangeSquared(1.0D, Double.NaN));
        assertFalse(ActionValidation.withinRangeSquared(-1.0D, 5.0D));
        assertFalse(ActionValidation.withinRangeSquared(1.0D, -5.0D));
    }

    @Test
    void twoDimensionsAreTheSameOnlyWhenBothAreNamedAndEqual() {
        assertTrue(ActionValidation.sameDimension(OVERWORLD, new ResourceLocation("minecraft", "overworld")));
        assertFalse(ActionValidation.sameDimension(OVERWORLD, NETHER));
        assertFalse(ActionValidation.sameDimension(null, null),
                "two unknown dimensions are not the same dimension");
        assertFalse(ActionValidation.sameDimension(OVERWORLD, null));
    }

    @Test
    void freshnessIsExclusiveAndAZeroExpiryIsDead() {
        assertTrue(ActionValidation.fresh(99L, 100L));
        assertFalse(ActionValidation.fresh(100L, 100L), "a session may not be used on the tick it dies");
        assertFalse(ActionValidation.fresh(101L, 100L));
        assertFalse(ActionValidation.fresh(0L, 0L), "an unset expiry is not eternal life");
    }

    @Test
    void aRevisionMustMatchExactlyInBothDirections() {
        assertTrue(ActionValidation.currentRevision(4L, 4L));
        assertFalse(ActionValidation.currentRevision(4L, 5L), "a stale action");
        assertFalse(ActionValidation.currentRevision(5L, 4L), "a forged one");
    }

    @Test
    void anInputSequenceMustStrictlyAdvance() {
        assertTrue(ActionValidation.advances(3, 4));
        assertFalse(ActionValidation.advances(3, 3), "a replay");
        assertFalse(ActionValidation.advances(3, 2), "a reordering");
    }

    @Test
    void aCustodyActionNeedsBothTheIdentityAndTheGeneration() {
        CustodyRecord record = new CustodyRecord(UUID.randomUUID(), true, true,
                CustodyOwner.guard(UUID.randomUUID()), 0L, null, OVERWORLD);
        UUID custodyId = record.getCustodyId();

        assertTrue(ActionValidation.matchesCustody(record, custodyId, 1L));
        assertFalse(ActionValidation.matchesCustody(record, UUID.randomUUID(), 1L),
                "a packet from a previous captivity of the same person");
        assertFalse(ActionValidation.matchesCustody(record, custodyId, 2L));
        assertFalse(ActionValidation.matchesCustody(null, custodyId, 1L));
        assertFalse(ActionValidation.matchesCustody(record, null, 1L));
    }

    @Test
    void theOlderGenerationsPacketsAreRefusedAfterAHandover() {
        CustodyRecord record = new CustodyRecord(UUID.randomUUID(), true, true,
                CustodyOwner.guard(UUID.randomUUID()), 0L, null, OVERWORLD);
        UUID custodyId = record.getCustodyId();
        long issued = record.getGeneration();

        record.setOwner(CustodyOwner.jail(3, null, OVERWORLD));
        record.bumpGeneration();

        assertFalse(ActionValidation.matchesCustody(record, custodyId, issued),
                "an escort packet issued to the arresting guard must stop working at the jail door");
        assertTrue(ActionValidation.matchesCustody(record, custodyId, record.getGeneration()));
    }

    @Test
    void aPhysicalActionNeedsBothTheGenerationAndTheRevision() {
        PhysicalRestraintState state = PhysicalRestraintState.empty(UUID.randomUUID(), true, OVERWORLD);

        assertTrue(ActionValidation.matchesPhysicalState(state, 1L, 0L));
        assertFalse(ActionValidation.matchesPhysicalState(state, 2L, 0L));
        assertFalse(ActionValidation.matchesPhysicalState(state, 1L, 1L));
        assertFalse(ActionValidation.matchesPhysicalState(null, 1L, 0L));

        PhysicalRestraintState next = state.nextGeneration();
        assertFalse(ActionValidation.matchesPhysicalState(next, 1L, 0L),
                "a new hold refuses the previous hold's packets");
    }

    @Test
    void nothingIsActionableAboutANullActor() {
        assertFalse(ActionValidation.actionable(null));
        assertFalse(ActionValidation.inReach(null, null, 5.0D));
        assertFalse(ActionValidation.hasLineOfSight(null, null));
        assertFalse(ActionValidation.holdsItem(null, null, null));
        assertFalse(ActionValidation.ownsMenu(null, 1));
    }
}
