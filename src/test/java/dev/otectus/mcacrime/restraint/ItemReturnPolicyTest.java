package dev.otectus.mcacrime.restraint;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a removal owes back, and what it does not (0.7.5 M2.7).
 *
 * <p>The specification names the failure this prevents: "a migration that creates a free extra cuff
 * on every login fails". An upgraded world converts every existing custody record into a physical
 * restraint, and every one of those restraints is gear that nobody actually supplied — so if removal
 * handed an item back on the strength of the definition naming one, an upgraded server would mint a
 * pair of cuffs for each converted prisoner, and again for each prisoner re-cuffed with them.
 *
 * <p>Three independent gates, asserted separately because each closes a different route to the same
 * duplication.
 */
class ItemReturnPolicyTest {

    @Test
    void systemIssuedGearReturnsNothing() {
        // What a lawful arrest applies when the arresting guard supplied no item.
        assertFalse(RemovalService.returnsItem(AppliedRestraint.Provenance.SYSTEM_ISSUED,
                AppliedRestraint.ReturnPolicy.DROP_AT_SUBJECT, RemovalService.Reason.KEY, true));
    }

    @Test
    void migratedGearReturnsNothing() {
        // The reconciler stamps LEGACY_CONVERSION with ReturnPolicy.NONE; either alone is enough.
        assertFalse(RemovalService.returnsItem(AppliedRestraint.Provenance.LEGACY_CONVERSION,
                AppliedRestraint.ReturnPolicy.DROP_AT_SUBJECT, RemovalService.Reason.KEY, true));
        assertFalse(RemovalService.returnsItem(AppliedRestraint.Provenance.PLAYER_OWNED,
                AppliedRestraint.ReturnPolicy.NONE, RemovalService.Reason.KEY, true));
    }

    @Test
    void gearSomebodySuppliedComesBack() {
        for (RemovalService.Reason reason : RemovalService.Reason.values()) {
            if (reason == RemovalService.Reason.BROKEN) {
                continue;
            }
            assertTrue(RemovalService.returnsItem(AppliedRestraint.Provenance.PLAYER_OWNED,
                            AppliedRestraint.ReturnPolicy.DROP_AT_SUBJECT, reason, false),
                    reason + " should return the item somebody actually spent");
        }
    }

    @Test
    void brokenGearIsDestroyedUnlessTheServerSaysOtherwise() {
        // Otherwise struggling out of cuffs is the cheapest way to keep a spare pair of cuffs.
        assertFalse(RemovalService.returnsItem(AppliedRestraint.Provenance.PLAYER_OWNED,
                AppliedRestraint.ReturnPolicy.DROP_AT_SUBJECT, RemovalService.Reason.BROKEN, false));
        assertTrue(RemovalService.returnsItem(AppliedRestraint.Provenance.PLAYER_OWNED,
                AppliedRestraint.ReturnPolicy.DROP_AT_SUBJECT, RemovalService.Reason.BROKEN, true));
    }
}
