package dev.otectus.mcacrime.compat;

import org.jetbrains.annotations.Nullable;
import java.util.List;
import java.util.UUID;

/**
 * Decides which accepted copies of the MCA: Quests bounty contract to fail, without naming an MCA:
 * Quests type, so the rules run in a plain unit test (0.7.5).
 *
 * <p>The contract is a board rather than a target, so a copy fails with {@code TARGET_LOST} only when
 * the board holds nothing its holder could claim — a board carrying nothing but their own warrant
 * counts as empty to them. Holders are read from MCA: Quests' persisted per-player data each time the
 * question is asked. Until 0.7.5 the adapter kept them in a process-lifetime set filled by the
 * acceptance event, so a restart emptied it, and a copy accepted before the restart never failed: it
 * sat un-completable in the journal until abandoned by hand.
 *
 * <p>Three kinds of copy are never failed, because each is owed a turn-in rather than a loss:
 * <ul>
 *   <li>the <b>credited</b> claimant's, whose resolution is being reported right now;</li>
 *   <li>a <b>satisfied</b> copy, which the bounty signal already reached and which is waiting for the
 *       player to turn it in at a guard;</li>
 *   <li>any copy of a holder with a <b>payment pending</b>: a bounty they earned is reserved on the
 *       ledger but not yet in their hands (a full inventory, say), and delivering it is what sends the
 *       signal that satisfies the copy.</li>
 * </ul>
 */
public final class BountyCopyReconciler {

    /** One online player's accepted copies, as the MCA: Quests adapter reads them. */
    public interface Holder<C> {

        UUID id();

        /** Every active copy of the bounty contract in this player's persisted quest data. */
        List<C> copies();

        /** Whether the bounty signal already reached this copy, which is now waiting to be turned in. */
        boolean satisfied(C copy);

        /** Fails the copy with {@code TARGET_LOST}. Must contain its own failures. */
        void fail(C copy);
    }

    /** What MCA: Crime's own ledger says about one player. */
    public interface Board {

        /** Whether anything is posted that this player could claim; their own warrant never counts. */
        boolean hasClaimable(UUID player);

        /** Whether a bounty this player earned is reserved but not yet delivered to them. */
        boolean paymentPending(UUID player);
    }

    private BountyCopyReconciler() {
    }

    /**
     * Fails every copy the rules above allow, and returns how many were failed.
     *
     * @param credited the claimant whose resolution triggered this pass, skipped outright; null when a
     *                 posting closed, a new one appeared, or a player just logged in
     */
    public static <C> int reconcile(Iterable<? extends Holder<C>> holders, @Nullable UUID credited, Board board) {
        int failed = 0;
        for (Holder<C> holder : holders) {
            if (holder.id().equals(credited)) {
                continue;
            }
            List<C> copies = holder.copies();
            if (copies.isEmpty()) {
                continue;
            }
            // Asked only of players who hold a copy, so a server where nobody took the contract pays one
            // quest-data read per online player and never touches the board.
            if (board.hasClaimable(holder.id()) || board.paymentPending(holder.id())) {
                continue;
            }
            for (C copy : copies) {
                if (!holder.satisfied(copy)) {
                    holder.fail(copy);
                    failed++;
                }
            }
        }
        return failed;
    }
}
