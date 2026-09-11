package dev.otectus.mcacrime;

import dev.otectus.mcacrime.detect.WitnessLoyaltyFilter;
import dev.otectus.mcacrime.detect.WitnessResult;
import dev.otectus.mcacrime.detect.WitnessSelection;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The witness-selection split: who is left to report, who kept quiet, and what the result says about
 * a crime the offender's family were the only ones to see.
 */
class WitnessLoyaltyFilterTest {

    private static UUID uuid(int n) {
        return UUID.fromString(String.format("00000000-0000-0000-0000-%012d", n));
    }

    private static List<WitnessSelection.Candidate> crowd(int count) {
        return java.util.stream.IntStream.rangeClosed(1, count)
                .mapToObj(i -> new WitnessSelection.Candidate(uuid(i), i))
                .collect(java.util.stream.Collectors.toList());
    }

    @Test
    void aLoyalCandidateIsRemovedFromTheReportingHalf() {
        WitnessLoyaltyFilter.Partition partition =
                WitnessLoyaltyFilter.partition(crowd(3), id -> id.equals(uuid(2)));

        assertEquals(2, partition.reporting().size());
        assertEquals(Set.of(uuid(2)), partition.loyal());
        assertFalse(partition.reporting().stream().anyMatch(c -> c.id().equals(uuid(2))));
    }

    @Test
    void noPredicateLeavesEverybodyReporting() {
        WitnessLoyaltyFilter.Partition partition = WitnessLoyaltyFilter.partition(crowd(3), null);

        assertEquals(3, partition.reporting().size());
        assertTrue(partition.loyal().isEmpty());
    }

    @Test
    void aCrimeSeenOnlyByLoyalFamilyIsUnwitnessed() {
        WitnessLoyaltyFilter.Partition partition = WitnessLoyaltyFilter.partition(crowd(3), id -> true);
        WitnessResult selected = WitnessSelection.select(partition.reporting(), 8, 3);
        WitnessResult result = WitnessResult.of(selected.witnessIds(), partition.loyal(),
                selected.scannedCandidates(), selected.totalWitnesses());

        // This is the gate the whole feature rests on: `witnessed` false is what skips the Heat
        // charge, the community standing drop and the family heart loss downstream.
        assertFalse(result.witnessed());
        assertTrue(result.witnessIds().isEmpty());
        assertEquals(3, result.loyalIds().size());
    }

    @Test
    void totalsCountTheReportingSideOnly() {
        WitnessLoyaltyFilter.Partition partition =
                WitnessLoyaltyFilter.partition(crowd(4), id -> id.equals(uuid(1)) || id.equals(uuid(2)));
        WitnessResult selected = WitnessSelection.select(partition.reporting(), 8, 4);
        WitnessResult result = WitnessResult.of(selected.witnessIds(), partition.loyal(),
                selected.scannedCandidates(), selected.totalWitnesses());

        assertEquals(2, result.totalWitnesses());
        assertEquals(2, result.count());
        assertFalse(result.truncated());
        assertTrue(result.witnessed());
    }

    @Test
    void theTwoSetsAreAlwaysDisjoint() {
        WitnessResult result = new WitnessResult(Set.of(uuid(1), uuid(2)), true, 2, 2,
                Set.of(uuid(2), uuid(3)));

        assertEquals(Set.of(uuid(1), uuid(2)), result.witnessIds());
        assertEquals(Set.of(uuid(3)), result.loyalIds());
    }
}
