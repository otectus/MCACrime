package dev.otectus.mcacrime.detect;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * Splits the candidate witnesses to one crime into the ones who will report it and the ones who will
 * not, with no Minecraft types involved so the split can be tested directly.
 *
 * <p>Loyalty is applied <em>here</em>, before selection, rather than as an exemption further down.
 * That is the whole design: {@code WitnessResult.witnessIds} is the single input to Heat, to community
 * standing, to family heart loss and to the observation set, so filtering once makes all four agree.
 * A downstream exemption would have to be repeated in four places and would be wrong in the fifth
 * place somebody adds later.
 */
public final class WitnessLoyaltyFilter {

    /** The two halves of a candidate list. Disjoint by construction. */
    public record Partition(List<WitnessSelection.Candidate> reporting, Set<UUID> loyal) {

        public Partition {
            reporting = reporting == null ? List.of() : List.copyOf(reporting);
            loyal = loyal == null ? Set.of() : Set.copyOf(loyal);
        }
    }

    private WitnessLoyaltyFilter() {
    }

    /**
     * Partitions {@code candidates} by {@code loyal}, which answers "will this villager keep quiet?".
     *
     * <p>A null or absent predicate leaves every candidate reporting, which is what the feature being
     * switched off has to look like.
     */
    public static Partition partition(List<WitnessSelection.Candidate> candidates, Predicate<UUID> loyal) {
        if (candidates == null || candidates.isEmpty()) {
            return new Partition(List.of(), Set.of());
        }
        if (loyal == null) {
            return new Partition(candidates, Set.of());
        }
        List<WitnessSelection.Candidate> reporting = new ArrayList<>(candidates.size());
        Set<UUID> quiet = new LinkedHashSet<>();
        for (WitnessSelection.Candidate candidate : candidates) {
            if (candidate == null || candidate.id() == null) {
                continue;
            }
            if (loyal.test(candidate.id())) {
                quiet.add(candidate.id());
            } else {
                reporting.add(candidate);
            }
        }
        return new Partition(reporting, quiet);
    }
}
