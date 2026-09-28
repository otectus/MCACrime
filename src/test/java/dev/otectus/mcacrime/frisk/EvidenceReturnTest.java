package dev.otectus.mcacrime.frisk;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Lawfully seized property comes back through the ledger that already exists (M5.3, spec §11.3).
 *
 * <p>The rule the specification states plainly is that a search is not an excuse to bypass the
 * existing recovery ledger. The structural guarantee is that a lawful seizure never reaches the
 * searcher's pockets at all: it becomes a {@code PropertyLot} owed to the subject, which the recovery
 * path delivers when custody ends, bail is paid or property is recovered. One stack, one place.
 */
class EvidenceReturnTest {

    private static final Path LEDGER = Path.of("src", "main", "java", "dev", "otectus", "mcacrime",
            "frisk", "SeizureLedger.java");
    private static final Path TRANSACTION = Path.of("src", "main", "java", "dev", "otectus",
            "mcacrime", "frisk", "FriskTransaction.java");

    private static String read(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    @Test
    void aLawfulSeizureBecomesAnEscrowLotOwedToItsOwner() {
        String ledger = read(LEDGER);
        assertTrue(ledger.contains("PropertyLot.ofStack(transferId, subject, taken, transferId, now)"),
                "the lot names the subject as owner and the transfer as its source");
        assertTrue(ledger.contains("data.putPropertyLot(lot).stored()"),
                "the lot goes into the escrow the recovery ledger already reads");
        assertTrue(ledger.contains("FriskRefusal.LEDGER_FULL"),
                "an escrow that cannot record the seizure refuses it rather than losing it");
    }

    @Test
    void aLawfulSeizureNeverAlsoGoesIntoTheSearchersPockets() {
        String transaction = read(TRANSACTION);
        int lawful = transaction.indexOf("if (session.seizureKind() == SeizureKind.LAWFUL_SEARCH && escrowSeizures()) {");
        int pocket = transaction.indexOf("searcher.getInventory().add(pocketed)");
        assertTrue(lawful > 0 && pocket > lawful,
                "the escrow branch returns before anything reaches the searcher's inventory");
        assertTrue(transaction.contains("return SeizureLedger.lawful("),
                "and it returns rather than falling through");
    }

    @Test
    void aCriminalSeizureFilesTheTheftTheRecoveryLedgerReads() {
        String ledger = read(LEDGER);
        assertTrue(ledger.contains("StolenGoodsLedger.commitTheft("),
                "criminal extraction goes through the existing two-phase theft transaction");
        assertTrue(ledger.contains("TheftExecutor.TheftResult"),
                "and records what was actually removed, not an estimate");
        assertFalse(ledger.contains("putStolenGoods("),
                "provenance is written by the transaction, never around it");
    }

    @Test
    void aPocketedTakeIsNeverRolledBackIntoASecondCopy() {
        String transaction = read(TRANSACTION);
        assertTrue(transaction.contains("return SeizureLedger.Outcome.done(transferId);"),
                "a take that reached the searcher's inventory commits");
        assertTrue(transaction.contains("searcher.drop(pocketed, false)"),
                "whatever did not fit is dropped at the searcher's feet rather than restored to the "
                        + "subject beside the part that did go in");
    }
}
