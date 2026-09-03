package dev.otectus.mcacrime.detect;

import java.util.Set;
import java.util.UUID;

/**
 * Who saw a crime, snapshotted at the moment it happened.
 *
 * <p>Replaces the bare witness count. A count answers "should this generate heat?", but it cannot
 * answer "may this villager talk about it?" — and that second question is the one that decides
 * whether installing a conversation mod leaks a murder nobody saw. Identities have to be captured at
 * detection time and never re-derived: rescanning later would let a villager who wandered past
 * afterwards "remember" something they never witnessed.
 *
 * <p>{@code witnessed} is a real component rather than {@code !witnessIds.isEmpty()}, because the two
 * legitimately diverge in both directions:
 * <ul>
 *   <li>A jailbreak is witnessed <em>by the authority</em> and has no villager UUIDs at all. The
 *       alternative — inventing a witness — is exactly what the privacy rules forbid.</li>
 *   <li>A record migrated from before identities were stored knows it was witnessed but not by whom.</li>
 * </ul>
 */
public record WitnessResult(Set<UUID> witnessIds, boolean witnessed,
                            int scannedCandidates, int totalWitnesses) {

    public WitnessResult {
        witnessIds = witnessIds == null ? Set.of() : Set.copyOf(witnessIds);
        scannedCandidates = Math.max(0, scannedCandidates);
        totalWitnesses = Math.max(witnessIds.size(), totalWitnesses);
    }

    /** How many witness identities are stored. May be less than {@link #totalWitnesses} under the cap. */
    public int count() {
        return witnessIds.size();
    }

    /** True when the stored set was capped and the real crowd was larger. */
    public boolean truncated() {
        return totalWitnesses > witnessIds.size();
    }

    /** Ordinary case: witnessed exactly when somebody was identified. */
    public static WitnessResult of(Set<UUID> ids, int scannedCandidates, int totalWitnesses) {
        Set<UUID> copy = ids == null ? Set.of() : Set.copyOf(ids);
        return new WitnessResult(copy, !copy.isEmpty(), scannedCandidates, totalWitnesses);
    }

    /** Nobody saw it. */
    public static WitnessResult none() {
        return new WitnessResult(Set.of(), false, 0, 0);
    }

    /**
     * Known to the law without a villager having seen it — a jailbreak, or a crime recorded by
     * command. Witnessed with no identities, deliberately.
     */
    public static WitnessResult official() {
        return new WitnessResult(Set.of(), true, 0, 0);
    }

    /**
     * A legacy or synthetic result carrying only the old boolean-plus-count shape. Used by the
     * migration path and by the deprecated {@code commitDirect} overload, where identities were never
     * captured and must not be fabricated.
     */
    public static WitnessResult legacy(boolean witnessed, int witnessCount) {
        int count = Math.max(0, witnessCount);
        return new WitnessResult(Set.of(), witnessed, count, count);
    }
}
