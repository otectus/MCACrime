package dev.otectus.mcacrime.api.model;

import java.util.Optional;
import java.util.UUID;

/**
 * One civic service contract, as much of it as anybody outside MCA: Crime needs (reference §12.1).
 *
 * <h2>The extension point a quest mod would use</h2>
 *
 * <p>Reference §12.1 ends with "a Quest representation is an optional presentation layer; Crime
 * retains the case and completion authority", and this record is the whole of that layer's input. It
 * carries what a quest entry would draw — the task, the progress, the deadline, the settlement — and
 * deliberately not the price, the case's contents or the offender's record, because a presentation
 * layer that could see those would be able to disagree with the screen the player already has.
 *
 * <p>There is no acceptance or completion method beside it, and that is the point: a companion may
 * show a contract and may not advance one. Work is credited from what MCA: Crime itself observed
 * happening, so a quest mod cannot hand a player a completed contract, and neither can a player
 * uninstalling the quest mod lose one.
 *
 * @param contractId     the contract's identity
 * @param caseId         the single case it settles
 * @param offender       who owes the work
 * @param community      the settlement, as {@code dimension/villageId}
 * @param task           the task's stable id
 * @param requiredUnits  how many accepted outputs finish it
 * @param completedUnits how many are in
 * @param deadline       the game time it lapses at
 * @param state          offered, active, completed, failed or cancelled
 */
public record CivicContractView(UUID contractId, UUID caseId, UUID offender, String community,
                                String task, int requiredUnits, int completedUnits, long deadline,
                                String state) {

    public CivicContractView {
        community = community == null ? "" : community;
        task = task == null ? "" : task;
        state = state == null ? "" : state;
        requiredUnits = Math.max(0, requiredUnits);
        completedUnits = Math.max(0, Math.min(requiredUnits, completedUnits));
    }

    /** How much is still owed. */
    public int remainingUnits() {
        return Math.max(0, requiredUnits - completedUnits);
    }

    /** The settlement key, when a consumer wants it parsed rather than printed. */
    public Optional<CrimeCommunityKey> communityKey() {
        return CrimeCommunityKey.tryParse(community);
    }
}
