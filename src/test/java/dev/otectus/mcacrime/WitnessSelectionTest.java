package dev.otectus.mcacrime;

import dev.otectus.mcacrime.detect.WitnessResult;
import dev.otectus.mcacrime.detect.WitnessSelection;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Witness capture: who is kept when a crowd is bigger than the cap, in what order it serialises, and
 * the cases where "witnessed" and "has witness identities" deliberately disagree.
 */
class WitnessSelectionTest {

    private static UUID uuid(int n) {
        return UUID.fromString(String.format("00000000-0000-0000-0000-%012d", n));
    }

    private static List<WitnessSelection.Candidate> crowd(int count) {
        List<WitnessSelection.Candidate> candidates = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            // Deliberately reversed: the highest-numbered UUID stands closest, so a test that
            // accidentally sorts by id instead of distance fails.
            candidates.add(new WitnessSelection.Candidate(uuid(i), count - i));
        }
        return candidates;
    }

    @Test
    void anEmptyCrowdIsUnwitnessed() {
        WitnessResult result = WitnessSelection.select(List.of(), 8, 0);

        assertFalse(result.witnessed());
        assertEquals(0, result.count());
        assertFalse(result.truncated());
    }

    @Test
    void everyoneIsKeptWhenTheCrowdFitsUnderTheCap() {
        WitnessResult result = WitnessSelection.select(crowd(5), 8, 5);

        assertTrue(result.witnessed());
        assertEquals(5, result.count());
        assertEquals(5, result.totalWitnesses());
        assertFalse(result.truncated());
    }

    /** The eight standing closest are the defensible eight to keep. */
    @Test
    void theCapKeepsTheNearestWitnesses() {
        WitnessResult result = WitnessSelection.select(crowd(20), 3, 20);

        assertEquals(3, result.count());
        // crowd() puts the highest ids nearest, so 18, 19 and 20 are the three closest.
        assertTrue(result.witnessIds().contains(uuid(20)));
        assertTrue(result.witnessIds().contains(uuid(19)));
        assertTrue(result.witnessIds().contains(uuid(18)));
        assertFalse(result.witnessIds().contains(uuid(1)));
    }

    /** Capping must not lose the fact that a crowd was there — only who exactly was in it. */
    @Test
    void theTrueCrowdSizeSurvivesTheCap() {
        WitnessResult result = WitnessSelection.select(crowd(20), 3, 25);

        assertTrue(result.truncated());
        assertEquals(20, result.totalWitnesses());
        assertEquals(25, result.scannedCandidates());
    }

    /** Stable NBT: the same crime must serialise identically every time it is saved. */
    @Test
    void storedWitnessesAreUuidOrdered() {
        WitnessResult first = WitnessSelection.select(crowd(6), 6, 6);
        List<WitnessSelection.Candidate> shuffled = new ArrayList<>(crowd(6));
        java.util.Collections.reverse(shuffled);
        WitnessResult second = WitnessSelection.select(shuffled, 6, 6);

        assertEquals(new ArrayList<>(first.witnessIds()), new ArrayList<>(second.witnessIds()));
    }

    @Test
    void aDuplicateCandidateIsCountedOnce() {
        WitnessResult result = WitnessSelection.select(
                List.of(new WitnessSelection.Candidate(uuid(1), 1.0),
                        new WitnessSelection.Candidate(uuid(1), 2.0)), 8, 2);

        assertEquals(1, result.count());
        assertEquals(1, result.totalWitnesses());
    }

    // ------------------------------------------------------------------ the two honest disagreements

    /**
     * A jailbreak is known to the law without any villager having seen it. Inventing a witness to make
     * the flag consistent is exactly what the privacy rules forbid, so the flag and the set diverge.
     */
    @Test
    void anOfficialRecordIsWitnessedWithNoWitnessIdentities() {
        WitnessResult official = WitnessResult.official();

        assertTrue(official.witnessed());
        assertEquals(0, official.count());
        assertTrue(official.witnessIds().isEmpty());
    }

    /** A migrated record knows it was seen but not by whom, and must not invent anyone. */
    @Test
    void aLegacyRecordKeepsItsFlagWithoutFabricatingWitnesses() {
        WitnessResult legacy = WitnessResult.legacy(true, 4);

        assertTrue(legacy.witnessed());
        assertTrue(legacy.witnessIds().isEmpty());
        assertEquals(4, legacy.totalWitnesses());
    }

    @Test
    void witnessIdsAreDefensivelyCopied() {
        java.util.Set<UUID> mutable = new java.util.HashSet<>();
        mutable.add(uuid(1));
        WitnessResult result = WitnessResult.of(mutable, 1, 1);

        mutable.add(uuid(2));

        assertEquals(1, result.count(), "the result must not see a later change to the source set");
    }
}
