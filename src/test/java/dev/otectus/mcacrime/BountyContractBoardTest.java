package dev.otectus.mcacrime;

import dev.otectus.mcacrime.bounty.BountyClaimKey;
import dev.otectus.mcacrime.bounty.BountyContract;
import dev.otectus.mcacrime.bounty.BountyContractBoard;
import dev.otectus.mcacrime.ledger.Warrant;
import dev.otectus.mcacrime.state.world.CrimeWorldData;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What may be posted on the bounty board, and — much more importantly — what may be collected off it.
 *
 * <p>Every assertion below is one property: a posting is identified by the warrant revision it was
 * made against. Everything the spec asks for follows from that. A revision replaces its predecessor
 * rather than joining it, so two hunters cannot hold two prices for the same offence. A closed warrant
 * takes its postings with it, so a contract never survives into "murder a lawful civilian for money".
 * And a resolution settles exactly the posting whose key it paid, exactly once — a second event for
 * the same key finds nothing, which is what a double-pay would have to get past.
 *
 * <p>Run against {@link CrimeWorldData} directly: world data can be built without a server, and a
 * server cannot be built in a unit test.
 */
class BountyContractBoardTest {

    private static final UUID TARGET = UUID.fromString("00000000-0000-0000-0000-0000000000c1");
    private static final UUID OTHER = UUID.fromString("00000000-0000-0000-0000-0000000000c2");
    private static final UUID WARRANT = UUID.fromString("00000000-0000-0000-0000-0000000000d1");
    private static final ResourceLocation OFFENSE = ResourceLocation.fromNamespaceAndPath("mcacrime", "murder");

    private static Warrant warrant(UUID offender, UUID id) {
        return Warrant.open(id, offender, OFFENSE, UUID.randomUUID(), 0L);
    }

    @Test
    void onePostingPerWarrantRevision() {
        CrimeWorldData data = new CrimeWorldData();
        Warrant open = warrant(TARGET, WARRANT);

        BountyContractBoard.post(data, open, "Target", 100L, true, true, 0L);
        BountyContractBoard.post(data, open, "Target", 100L, true, true, 0L);

        List<BountyContract> posted = BountyContractBoard.openContracts(data);
        assertEquals(1, posted.size(), "the same revision was posted twice");
        assertEquals(1L, posted.get(0).warrantRevision());
    }

    @Test
    void aRevisionReplacesThePostingItSupersedes() {
        CrimeWorldData data = new CrimeWorldData();
        Warrant open = warrant(TARGET, WARRANT);
        BountyContract first = BountyContractBoard.post(data, open, "Target", 100L, true, true, 0L);

        Warrant revised = open.revised(UUID.randomUUID(), OFFENSE, 200L);
        BountyContract second = BountyContractBoard.post(data, revised, "Target", 250L, true, true, 0L);

        List<BountyContract> posted = BountyContractBoard.openContracts(data);
        assertEquals(1, posted.size(), "the superseded posting is still on the board");
        assertEquals(second.contractId(), posted.get(0).contractId());
        assertEquals(2L, posted.get(0).warrantRevision());
        assertEquals(250L, posted.get(0).principalReward());
        assertFalse(first.contractId().equals(second.contractId()));
    }

    @Test
    void closingTheWarrantWithdrawsItsPostings() {
        CrimeWorldData data = new CrimeWorldData();
        Warrant open = warrant(TARGET, WARRANT);
        BountyContract contract = BountyContractBoard.post(data, open, "Target", 100L, true, true, 0L);

        List<UUID> withdrawn = BountyContractBoard.withdraw(data, open.id());

        assertEquals(List.of(contract.contractId()), withdrawn);
        assertTrue(BountyContractBoard.openContracts(data).isEmpty());
    }

    @Test
    void aResolutionSettlesItsOwnPostingExactlyOnce() {
        CrimeWorldData data = new CrimeWorldData();
        Warrant open = warrant(TARGET, WARRANT);
        BountyContract contract = BountyContractBoard.post(data, open, "Target", 100L, true, true, 0L);
        BountyClaimKey key = contract.claimKey();

        assertEquals(contract.contractId(), BountyContractBoard.withdrawClaimed(data, key).orElse(null));
        assertTrue(BountyContractBoard.withdrawClaimed(data, key).isEmpty(),
                "a second resolution for the same key withdrew a second contract");
        assertTrue(BountyContractBoard.openContracts(data).isEmpty());
    }

    @Test
    void aResolutionLeavesEverybodyElsesPostingAlone() {
        CrimeWorldData data = new CrimeWorldData();
        BountyContract mine = BountyContractBoard.post(data, warrant(TARGET, WARRANT), "Target",
                100L, true, true, 0L);
        UUID otherWarrant = UUID.fromString("00000000-0000-0000-0000-0000000000d2");
        BountyContract theirs = BountyContractBoard.post(data, warrant(OTHER, otherWarrant), "Other",
                80L, true, true, 0L);

        BountyContractBoard.withdrawClaimed(data, mine.claimKey());

        List<BountyContract> posted = BountyContractBoard.openContracts(data);
        assertEquals(1, posted.size());
        assertEquals(theirs.contractId(), posted.get(0).contractId());
    }

    @Test
    void aClaimAgainstAnOlderRevisionSettlesNothing() {
        CrimeWorldData data = new CrimeWorldData();
        Warrant open = warrant(TARGET, WARRANT);
        BountyContract stale = BountyContractBoard.post(data, open, "Target", 100L, true, true, 0L);
        BountyContractBoard.post(data, open.revised(UUID.randomUUID(), OFFENSE, 200L), "Target",
                250L, true, true, 0L);

        assertTrue(BountyContractBoard.withdrawClaimed(data, stale.claimKey()).isEmpty(),
                "a key from a superseded revision withdrew the current posting");
        assertEquals(1, BountyContractBoard.openContracts(data).size());
    }
}
