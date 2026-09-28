package dev.otectus.mcacrime.frisk;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The validations of specification §11.2, and the exactly-once property they protect (M5.2).
 *
 * <p>{@link FriskValidation} is pure, so every combination can be exercised here rather than
 * asserted about. The transaction that consumes it is checked structurally: the order of debit,
 * credit and rollback is the correctness argument, and an inversion of it is a duplication bug.
 */
class FriskTransactionTest {

    private static FriskValidation.Facts all(boolean value) {
        return new FriskValidation.Facts(value, value, value, value, value, value, value, value,
                value, value, !value);
    }

    private static FriskValidation.Facts good() {
        return all(true);
    }

    @Test
    void everyConditionIsCheckedAndTheFirstFailureIsReported() {
        assertEquals(FriskRefusal.NONE, FriskValidation.check(good()));

        FriskValidation.Facts f = good();
        assertEquals(FriskRefusal.NO_SESSION, FriskValidation.check(with(f, 0)));
        assertEquals(FriskRefusal.NOT_YOUR_MENU, FriskValidation.check(with(f, 1)));
        assertEquals(FriskRefusal.SUBJECT_UNAVAILABLE, FriskValidation.check(with(f, 2)));
        assertEquals(FriskRefusal.SUBJECT_UNAVAILABLE, FriskValidation.check(with(f, 3)));
        assertEquals(FriskRefusal.OUT_OF_REACH, FriskValidation.check(with(f, 4)));
        assertEquals(FriskRefusal.NOT_RESTRAINED, FriskValidation.check(with(f, 5)));
        assertEquals(FriskRefusal.CUSTODY_CHANGED, FriskValidation.check(with(f, 6)));
        assertEquals(FriskRefusal.SLOT_CHANGED, FriskValidation.check(with(f, 7)));
        assertEquals(FriskRefusal.DESTINATION_REFUSED, FriskValidation.check(with(f, 8)));
        assertEquals(FriskRefusal.TOO_SOON, FriskValidation.check(with(f, 9)));
    }

    /** One fact flipped false, by position, so the ordering itself is what is being asserted. */
    private static FriskValidation.Facts with(FriskValidation.Facts base, int index) {
        boolean[] v = {base.sessionLive(), base.ownsMenu(), base.subjectAlive(), base.sameDimension(),
                base.withinReach(), base.restraintSatisfied(), base.custodyMatches(),
                base.slotMatches(), base.destinationTakesIt(), base.delayElapsed()};
        v[index] = false;
        return new FriskValidation.Facts(v[0], v[1], v[2], v[3], v[4], v[5], v[6], v[7], v[8], v[9],
                base.alreadyApplied());
    }

    @Test
    void targetDiesDisconnectsOrIsReleased() {
        // Dead or gone, in another world, or released from the custody the search was opened under:
        // three different facts, and none of them may allow a further extraction.
        assertNotEquals(FriskRefusal.NONE, FriskValidation.check(with(good(), 2)));
        assertNotEquals(FriskRefusal.NONE, FriskValidation.check(with(good(), 3)));
        assertNotEquals(FriskRefusal.NONE, FriskValidation.check(with(good(), 6)));
        // And each of them also ends the session, rather than merely refusing this one transfer.
        assertFalse(FriskValidation.sessionStillValid(with(good(), 2)));
        assertFalse(FriskValidation.sessionStillValid(with(good(), 3)));
        assertFalse(FriskValidation.sessionStillValid(with(good(), 6)));
    }

    @Test
    void aFullInventoryRefusesTheTakeButKeepsTheSearchOpen() {
        // Condition 6 is about this one stack, not about the session: the searcher can make room and
        // try again without the screen closing under them.
        assertEquals(FriskRefusal.DESTINATION_REFUSED, FriskValidation.check(with(good(), 8)));
        assertTrue(FriskValidation.sessionStillValid(with(good(), 8)));
    }

    @Test
    void concurrentSearchersAndCursorClose() {
        // Two searchers reaching for the same stack: the first take changes the slot's contents, so
        // the second one's revision no longer matches and it is refused. Nothing is taken twice.
        assertEquals(FriskRefusal.SLOT_CHANGED, FriskValidation.check(with(good(), 7)));
        // Closing the screen does not end the world: a transfer naming a menu this player does not
        // have open is refused before anything is read.
        assertEquals(FriskRefusal.NOT_YOUR_MENU, FriskValidation.check(with(good(), 1)));
    }

    @Test
    void crashBetweenSourceAndDestination() {
        // A replayed transfer is recognised by its id and refused before any state is touched, which
        // is what turns "replay after a crash" into a no-op rather than a second take.
        FriskValidation.Facts replayed = new FriskValidation.Facts(true, true, true, true, true, true,
                true, true, true, true, true);
        assertEquals(FriskRefusal.ALREADY_DONE, FriskValidation.check(replayed));
        assertEquals(FriskRefusal.ALREADY_DONE, FriskValidation.check(
                new FriskValidation.Facts(false, false, false, false, false, false, false, false,
                        false, false, true)),
                "the replay check comes first, so a stale replay is never re-applied by accident");
    }

    @Test
    void transferIdsAreDerivedAndDistinctPerTake() {
        UUID first = SeizureLedger.transferId(7L, 3, 991, 1);
        assertEquals(first, SeizureLedger.transferId(7L, 3, 991, 1), "the same take has the same id");
        assertNotEquals(first, SeizureLedger.transferId(7L, 4, 991, 1));
        assertNotEquals(first, SeizureLedger.transferId(7L, 3, 992, 1));
        assertNotEquals(first, SeizureLedger.transferId(7L, 3, 991, 2));
        assertNotEquals(first, SeizureLedger.transferId(8L, 3, 991, 1));
    }

    @Test
    void aSlotsRevisionChangesWithEverythingThatIdentifiesIt() {
        int base = FriskRevision.combine(17, 3, 41);
        assertNotEquals(base, FriskRevision.combine(18, 3, 41));
        assertNotEquals(base, FriskRevision.combine(17, 4, 41));
        assertNotEquals(base, FriskRevision.combine(17, 3, 42));
        assertEquals(FriskRevision.EMPTY, FriskRevision.combine(17, 0, 41),
                "an empty slot has the empty revision, whatever used to be in it");
        assertNotEquals(FriskRevision.EMPTY, base, "an occupied slot never reads as empty");
    }

    /** The ordering the exactly-once property rests on, asserted against the source. */
    @Test
    void debitThenCreditThenRollbackNeverTheOtherWayRound() {
        String source = read(Path.of("src", "main", "java", "dev", "otectus", "mcacrime", "frisk",
                "FriskTransaction.java"));
        int validate = source.indexOf("FriskValidation.check(facts)");
        int debit = source.indexOf("provider.extract(subject, ref, intendedCount)");
        int credit = source.indexOf("credit(session, searcher, subject, data");
        int rollback = source.indexOf("provider.restore(subject, ref, taken)");
        assertTrue(validate > 0 && debit > validate, "nothing is debited before the conditions pass");
        assertTrue(credit > debit, "the destination is credited after the source is debited");
        assertTrue(rollback > credit, "the rollback is what a failed credit runs");
        assertTrue(source.contains("subject.spawnAtLocation(taken)"),
                "a rollback that cannot reach the slot still keeps the stack in the world");

        // The inventory is asked before the theft is filed, the theft is filed before the stack is
        // pocketed, and the theft's supplier mutates nothing.
        int ask = source.indexOf("InventoryCapacity.wouldTake(searcher.getInventory(), taken)");
        int file = source.indexOf("SeizureLedger.criminal(");
        int pocket = source.indexOf("searcher.getInventory().add(pocketed)");
        assertTrue(ask > 0 && file > ask && pocket > file,
                "ask, then file, then pocket: any other order can duplicate or lose the stack");
        assertTrue(source.contains("taken::copy"),
                "the provenance supplier reports what was debited and never debits again");
    }

    private static String read(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }
}
