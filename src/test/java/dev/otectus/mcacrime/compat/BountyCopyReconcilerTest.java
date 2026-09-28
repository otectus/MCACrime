package dev.otectus.mcacrime.compat;

import dev.otectus.mcacrime.bounty.BountyClaimKey;
import dev.otectus.mcacrime.bounty.BountyPayments;
import dev.otectus.mcacrime.bounty.BountyResolutionType;
import dev.otectus.mcacrime.bounty.BountyService;
import dev.otectus.mcacrime.state.world.CrimeWorldData;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Which accepted copies of the MCA: Quests bounty contract fail, and which are owed a turn-in (0.7.5).
 *
 * <p>The regression these pin: until 0.7.5 holders were a static set filled by the acceptance event, so
 * after a restart the set was empty and no copy accepted before it ever failed. The reconciler has no
 * notion of acceptance at all; a holder is whoever's persisted quest data carries a copy, which is what
 * the fakes below model.
 */
class BountyCopyReconcilerTest {

    private static final UUID HUNTER = UUID.randomUUID();
    private static final UUID OTHER = UUID.randomUUID();

    @Test
    void aCopyReadFromPersistedDataFailsWhenTheBoardIsEmpty() {
        // No acceptance was ever observed in this "process": the copy exists only in the quest data.
        FakeHolder hunter = new FakeHolder(HUNTER, "copy");
        int failed = BountyCopyReconciler.reconcile(List.of(hunter), null, board(Set.of(), Set.of()));
        assertEquals(1, failed);
        assertEquals(List.of("copy"), hunter.failed);
    }

    @Test
    void theCreditedClaimantIsSkipped() {
        FakeHolder hunter = new FakeHolder(HUNTER, "credited copy");
        FakeHolder rival = new FakeHolder(OTHER, "rival copy");
        BountyCopyReconciler.reconcile(List.of(hunter, rival), HUNTER, board(Set.of(), Set.of()));
        assertTrue(hunter.failed.isEmpty(), "the ledger just paid this player; their copy is owed a turn-in");
        assertEquals(List.of("rival copy"), rival.failed);
    }

    @Test
    void aSatisfiedCopyWaitingForTurnInIsKept() {
        FakeHolder hunter = new FakeHolder(HUNTER, "satisfied", "unsatisfied");
        hunter.satisfied.add("satisfied");
        BountyCopyReconciler.reconcile(List.of(hunter), null, board(Set.of(), Set.of()));
        assertEquals(List.of("unsatisfied"), hunter.failed,
                "a later board change must not take back a copy the bounty signal already satisfied");
    }

    @Test
    void aHolderWhoCanStillClaimSomethingKeepsTheirCopy() {
        FakeHolder hunter = new FakeHolder(HUNTER, "copy");
        FakeHolder outlaw = new FakeHolder(OTHER, "own warrant only");
        // The board reports claimability per player: the outlaw's own posting is not claimable by them.
        BountyCopyReconciler.reconcile(List.of(hunter, outlaw), null, board(Set.of(HUNTER), Set.of()));
        assertTrue(hunter.failed.isEmpty());
        assertEquals(List.of("own warrant only"), outlaw.failed);
    }

    @Test
    void aPendingPaymentKeepsTheCopyUntilItIsDelivered() {
        FakeHolder hunter = new FakeHolder(HUNTER, "copy");
        BountyCopyReconciler.reconcile(List.of(hunter), null, board(Set.of(), Set.of(HUNTER)));
        assertTrue(hunter.failed.isEmpty(), "delivering the payment is what satisfies the copy");
    }

    @Test
    void aLoginWithAnEmptyBoardFailsTheCopy() {
        // The login path reconciles the one player who just joined, with nobody credited.
        FakeHolder returning = new FakeHolder(HUNTER, "copy from last week");
        assertEquals(1, BountyCopyReconciler.reconcile(List.of(returning), null, board(Set.of(), Set.of())));
    }

    @Test
    void playersWithoutACopyNeverTouchTheBoard() {
        CountingBoard counting = new CountingBoard();
        BountyCopyReconciler.reconcile(List.of(new FakeHolder(HUNTER), new FakeHolder(OTHER)), null, counting);
        assertEquals(0, counting.asked, "a server where nobody took the contract must not scan the board");
    }

    @Test
    void anUndeliveredPaymentIsPendingUntilItIsDelivered() {
        CrimeWorldData data = new CrimeWorldData();
        BountyClaimKey key = new BountyClaimKey(UUID.randomUUID(), UUID.randomUUID(), 1);
        assertFalse(BountyService.hasUndeliveredPayment(data, HUNTER));

        assertTrue(BountyPayments.reserve(data, key, HUNTER, 100, 1, BountyResolutionType.KILLED, "test:bank"));
        assertTrue(BountyService.hasUndeliveredPayment(data, HUNTER), "reserved, not yet delivered");
        assertFalse(BountyService.hasUndeliveredPayment(data, OTHER), "somebody else's payment is not theirs");

        BountyPayments.deliver(data, key, HUNTER, "test:bank", amount -> 40, 2); // part did not fit
        assertTrue(BountyService.hasUndeliveredPayment(data, HUNTER), "a remainder is still owed");

        assertTrue(BountyPayments.deliver(data, key, HUNTER, "test:bank", amount -> 0, 3));
        assertFalse(BountyService.hasUndeliveredPayment(data, HUNTER));
    }

    @Test
    void anAmbiguousPaymentCountsAsPending() {
        CrimeWorldData data = new CrimeWorldData();
        BountyClaimKey key = new BountyClaimKey(UUID.randomUUID(), UUID.randomUUID(), 1);
        assertTrue(BountyPayments.reserve(data, key, HUNTER, 100, 1, BountyResolutionType.KILLED, "test:bank"));
        BountyPayments.deliver(data, key, HUNTER, "test:bank", amount -> -1, 2); // outcome unknown
        assertTrue(BountyService.hasUndeliveredPayment(data, HUNTER),
                "an unsettled debt may yet be delivered, so the copy is kept for it");
    }

    private static BountyCopyReconciler.Board board(Set<UUID> claimable, Set<UUID> pending) {
        return new BountyCopyReconciler.Board() {
            @Override
            public boolean hasClaimable(UUID player) {
                return claimable.contains(player);
            }

            @Override
            public boolean paymentPending(UUID player) {
                return pending.contains(player);
            }
        };
    }

    private static final class CountingBoard implements BountyCopyReconciler.Board {
        int asked;

        @Override
        public boolean hasClaimable(UUID player) {
            asked++;
            return false;
        }

        @Override
        public boolean paymentPending(UUID player) {
            asked++;
            return false;
        }
    }

    private static final class FakeHolder implements BountyCopyReconciler.Holder<String> {
        private final UUID id;
        private final List<String> copies;
        final Set<String> satisfied = new HashSet<>();
        final List<String> failed = new ArrayList<>();

        FakeHolder(UUID id, String... copies) {
            this.id = id;
            this.copies = List.of(copies);
        }

        @Override
        public UUID id() {
            return id;
        }

        @Override
        public List<String> copies() {
            return copies;
        }

        @Override
        public boolean satisfied(String copy) {
            return satisfied.contains(copy);
        }

        @Override
        public void fail(String copy) {
            failed.add(copy);
        }
    }
}
