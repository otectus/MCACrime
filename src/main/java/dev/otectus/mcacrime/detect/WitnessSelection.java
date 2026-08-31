package dev.otectus.mcacrime.detect;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/**
 * Chooses which witnesses to keep when a crowd is larger than the cap, with no Minecraft types
 * involved so the rule can be tested directly.
 *
 * <p>There are two different sorts here and both are load-bearing:
 * <ol>
 *   <li><b>Nearest-first, to select.</b> If twenty villagers are in range and we store eight, the
 *       eight standing closest are the defensible choice — they are the ones who plainly saw it.</li>
 *   <li><b>UUID-ascending, to serialize.</b> Once chosen, the set is written in a stable order so
 *       the same crime produces byte-identical NBT every time. Without it, save files differ run to
 *       run and round-trip tests become flaky for no gameplay reason.</li>
 * </ol>
 *
 * <p>The true crowd size is preserved separately even when the stored set is capped, so downstream
 * code can say "and a dozen others saw it" without pretending to know who they were.
 */
public final class WitnessSelection {

    /** One candidate witness: an identity and how far it was from the victim. */
    public record Candidate(UUID id, double distanceSq) {
    }

    private static final Comparator<Candidate> NEAREST_FIRST =
            Comparator.comparingDouble(Candidate::distanceSq)
                    .thenComparing(candidate -> candidate.id().toString());

    private WitnessSelection() {
    }

    /**
     * Keeps at most {@code maxStored} nearest candidates, returning them UUID-sorted with the full
     * crowd size recorded.
     *
     * @param scannedCandidates how many entities the scan looked at, before the line-of-sight filter
     */
    public static WitnessResult select(List<Candidate> candidates, int maxStored, int scannedCandidates) {
        if (candidates == null || candidates.isEmpty()) {
            return new WitnessResult(Set.of(), false, Math.max(0, scannedCandidates), 0);
        }
        int cap = Math.max(1, maxStored);
        List<Candidate> ordered = new ArrayList<>(candidates);
        ordered.sort(NEAREST_FIRST);

        // Nearest-first to pick, then UUID-ascending to store. A TreeSet gives the second sort for
        // free and also collapses a duplicate id, which a double-registered entity could produce.
        Set<UUID> kept = new TreeSet<>();
        for (Candidate candidate : ordered) {
            if (kept.size() == cap) {
                break;
            }
            if (candidate != null && candidate.id() != null) {
                kept.add(candidate.id());
            }
        }
        int total = distinctCount(ordered);
        return new WitnessResult(new LinkedHashSet<>(kept), !kept.isEmpty(),
                Math.max(scannedCandidates, total), total);
    }

    private static int distinctCount(List<Candidate> candidates) {
        Set<UUID> seen = new TreeSet<>();
        for (Candidate candidate : candidates) {
            if (candidate != null && candidate.id() != null) {
                seen.add(candidate.id());
            }
        }
        return seen.size();
    }
}
