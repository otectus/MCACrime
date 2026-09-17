package dev.otectus.mcacrime.civic;

import dev.otectus.mcacrime.api.model.CrimeCommunityKey;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The contract's state machine, which is where "a sentence is reduced exactly once" actually lives.
 *
 * <p>It could have lived in {@code CivicWorkService} as a discipline at one call site — check the
 * state, settle the case, write the new state — and that is precisely the arrangement that produces a
 * double settlement the first time a second code path calls it. Putting the rule in the record means
 * the second completion has nothing to return but the contract it was already given.
 */
class ServiceContractTest {

    private static final CrimeCommunityKey COMMUNITY =
            new CrimeCommunityKey(new ResourceLocation("minecraft", "overworld"), 3);
    private static final UUID OFFENDER = UUID.fromString("00000000-0000-0000-0000-0000000000c1");
    private static final UUID CASE = UUID.fromString("00000000-0000-0000-0000-0000000000c2");

    private static ServiceContract offered() {
        return ServiceContract.offered(UUID.randomUUID(), CASE, OFFENDER, true, COMMUNITY,
                CivicTask.VICTIM_AMENDS, 2, 100L, 100L + ServiceContract.OFFER_VALIDITY_TICKS, null);
    }

    @Test
    void anOfferStartsUnacceptedAndUnsettled() {
        ServiceContract contract = offered();

        assertEquals(ServiceContract.State.OFFERED, contract.state());
        assertTrue(contract.open());
        assertFalse(contract.active(), "an offer nobody accepted must not accrue progress");
        assertFalse(contract.satisfied());
        assertEquals(2, contract.remainingUnits());
    }

    @Test
    void progressOnlyCountsAfterAcceptance() {
        ServiceContract contract = offered();

        assertSame(contract, contract.progressed(1, 110L),
                "work done before the contract is taken up is not work done under it");

        ServiceContract active = contract.accepted(110L);
        assertEquals(ServiceContract.State.ACTIVE, active.state());
        assertEquals(1, active.progressed(1, 120L).completedUnits());
    }

    @Test
    void acceptingTwiceKeepsTheProgressAlreadyMade() {
        ServiceContract active = offered().accepted(110L).progressed(1, 120L);

        ServiceContract again = active.accepted(130L);

        assertSame(active, again,
                "a reconnect re-sending the acceptance must not zero the work already credited");
        assertEquals(1, again.completedUnits());
    }

    @Test
    void progressIsClampedAtTheObligation() {
        ServiceContract active = offered().accepted(110L);

        ServiceContract flooded = active.progressed(99, 120L);

        assertEquals(2, flooded.completedUnits(), "a burst of signals cannot over-credit a contract");
        assertTrue(flooded.satisfied());
        assertEquals(0, flooded.remainingUnits());
    }

    @Test
    void completionIsRefusedUntilTheWorkIsDone() {
        ServiceContract halfway = offered().accepted(110L).progressed(1, 120L);

        assertSame(halfway, halfway.completed(130L),
                "an unsatisfied contract must not be completable, whatever calls it");
    }

    @Test
    void completionHappensExactlyOnce() {
        ServiceContract done = offered().accepted(110L).progressed(2, 120L);

        ServiceContract completed = done.completed(130L);
        ServiceContract again = completed.completed(140L);

        assertEquals(ServiceContract.State.COMPLETED, completed.state());
        assertSame(completed, again,
                "the second completion is the double settlement this whole state machine exists to "
                        + "prevent; it must find nothing left to do");
        assertFalse(completed.open());
    }

    @Test
    void failureLeavesTheOriginalSentenceStanding() {
        ServiceContract active = offered().accepted(110L).progressed(1, 120L);

        ServiceContract failed = active.failed(999_999L);

        assertEquals(ServiceContract.State.FAILED, failed.state());
        assertTrue(failed.state().leavesSentenceStanding(),
                "nothing was ever taken off the fine, so failing restores it by never having left it");
        assertEquals(1, failed.completedUnits(), "the work that was done is kept as history");
        assertSame(failed, failed.completed(1_000_000L),
                "a lapsed contract cannot be completed afterwards");
    }

    @Test
    void cancellationIsTerminalAndAlsoLeavesTheSentence() {
        ServiceContract cancelled = offered().cancelled(120L);

        assertEquals(ServiceContract.State.CANCELLED, cancelled.state());
        assertTrue(cancelled.state().leavesSentenceStanding());
        assertSame(cancelled, cancelled.accepted(130L), "a withdrawn offer cannot be taken up");
        assertSame(cancelled, cancelled.failed(999_999L));
    }

    @Test
    void expiryIsReadOffTheDeadlineAndNotOffTheState() {
        ServiceContract contract = offered();

        assertFalse(contract.expired(contract.deadline() - 1));
        assertTrue(contract.expired(contract.deadline()));
        assertTrue(contract.expired(contract.deadline() + 1));
    }

    @Test
    void itRoundTripsThroughNbt() {
        ServiceContract active = offered().accepted(110L).progressed(1, 120L);

        ServiceContract loaded = ServiceContract.load(active.save());

        assertNotNull(loaded);
        assertEquals(active, loaded);
    }

    @Test
    void anUnreadableRowIsNullSoTheCallerCanQuarantineIt() {
        CompoundTag broken = offered().save();
        broken.putString("task", "no_such_task");

        assertNull(ServiceContract.load(broken),
                "a contract nobody can parse must be handed back for quarantine, not guessed at");
        assertNull(ServiceContract.load(new CompoundTag()));
        assertNull(ServiceContract.load(null));
    }
}
