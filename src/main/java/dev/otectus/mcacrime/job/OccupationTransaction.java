package dev.otectus.mcacrime.job;

import dev.otectus.mcacrime.compat.OccupationSnapshot;
import dev.otectus.mcacrime.state.world.WorksiteRef;

import javax.annotation.Nullable;

import java.util.Optional;

/**
 * The one ordered sequence every route into a Thief occupation goes through (0.7.2 §9.3).
 *
 * <p>Nothing outside this class is allowed to change a villager's profession for crime reasons. That
 * is the whole point of §10.1's "the public workstation path and the ambient assignment sweep must
 * converge on the same transition service": before this, the sweep, the operator command and the API
 * each wrote the record and then separately hoped the presentation had followed.
 *
 * <h2>Rollback, and its limits</h2>
 *
 * <p>What is undone is what this transaction did: a ticket it took, a claim it released, the
 * profession, trading XP, merchant offers, MCA's clothing string and family-tree profession, and the
 * occupational memories. What is <em>not</em> undone is anything outside that list — dirty flags,
 * consumed randomness, and any state another mod changed while this ran. If ownership of the old
 * station changed hands in between, or the restore itself fails, the result is a suspension with a
 * diagnostic rather than a pretence that the villager is clean.
 *
 * <p>No event is published and no packet is sent from here. The caller does that, and only for a
 * committed result.
 */
public final class OccupationTransaction {

    private OccupationTransaction() {
    }

    /**
     * Runs the transition. Never throws, and never leaves a half-applied villager silently.
     *
     * @param mutator the world changes, so this class can be exercised against a failing fake
     */
    public static OccupationTransitionResult run(OccupationMutator mutator, OccupationRequest request) {
        if (mutator == null || request == null) {
            return OccupationTransitionResult.rejected(OccupationTransitionReason.NOT_MUTABLE);
        }
        WorksiteRef site = request.worksite();
        if (site == null && request.source().requiresStation()) {
            // Spec §10.1: the settlement path must not create stationless thieves.
            return OccupationTransitionResult.rejected(OccupationTransitionReason.NO_WORKSITE);
        }

        Optional<OccupationSnapshot> captured;
        try {
            captured = mutator.snapshot();
        } catch (Throwable t) {
            return OccupationTransitionResult.rejected(OccupationTransitionReason.NOT_LOADED,
                    String.valueOf(t));
        }
        if (captured.isEmpty()) {
            return OccupationTransitionResult.rejected(OccupationTransitionReason.NOT_LOADED);
        }
        OccupationSnapshot snapshot = captured.get();
        if (snapshot.temporaryOccupant()) {
            // MCA's temporary inn occupants are discarded or reset on expiry regardless of trading XP,
            // so the floor that protects every other committed Thief would not protect this one.
            return OccupationTransitionResult.rejected(OccupationTransitionReason.PROTECTED_NPC);
        }
        if (!request.rebindOnly() && !snapshot.restorable()) {
            // Refusing before any mutation is the point: a villager whose previous profession could not
            // be read has no state to roll back to, and committing anyway is how one becomes permanently
            // stuck as a Thief because a binding was missing for one tick.
            return OccupationTransitionResult.rejected(OccupationTransitionReason.CLASSIFICATION_UNAVAILABLE);
        }

        boolean tookNewTicket = false;
        boolean releasedOldSite = false;
        WorksiteRef oldSite = snapshot.jobSite();
        try {
            if (site != null) {
                boolean claimed = request.adoptExisting() ? mutator.adoptTicket(site) : mutator.reserveTicket(site);
                if (!claimed) {
                    return OccupationTransitionResult.rejected(OccupationTransitionReason.WORKSITE_LOST);
                }
                tookNewTicket = !request.adoptExisting();
            }

            if (oldSite != null && !oldSite.equals(site)) {
                releasedOldSite = mutator.releaseOldJobSite(oldSite);
            }
            mutator.clearOccupationalMemories();

            if (!request.rebindOnly() && !mutator.applyProfession()) {
                return rollback(mutator, snapshot, site, tookNewTicket, oldSite, releasedOldSite,
                        OccupationTransitionReason.VERIFICATION_FAILED, "MCA profession did not read back");
            }
            if (!request.rebindOnly() && !mutator.clearOffers()) {
                return rollback(mutator, snapshot, site, tookNewTicket, oldSite, releasedOldSite,
                        OccupationTransitionReason.MUTATION_FAILED, "merchant offers could not be cleared");
            }
            if (site != null && !mutator.setJobSite(site)) {
                return rollback(mutator, snapshot, site, tookNewTicket, oldSite, releasedOldSite,
                        OccupationTransitionReason.MUTATION_FAILED, "job-site memory could not be written");
            }
            if (!mutator.applyXpFloor()) {
                return rollback(mutator, snapshot, site, tookNewTicket, oldSite, releasedOldSite,
                        OccupationTransitionReason.MUTATION_FAILED, "trading XP floor could not be applied");
            }
            if (!mutator.verify(site)) {
                return rollback(mutator, snapshot, site, tookNewTicket, oldSite, releasedOldSite,
                        OccupationTransitionReason.VERIFICATION_FAILED, "post-change verification disagreed");
            }
        } catch (Throwable t) {
            return rollback(mutator, snapshot, site, tookNewTicket, oldSite, releasedOldSite,
                    OccupationTransitionReason.MUTATION_FAILED, String.valueOf(t));
        }
        return OccupationTransitionResult.committed(request.targetStatus());
    }

    /**
     * Undoes what this transaction did, in the reverse order it did it.
     *
     * <p>Tickets first, because a restored profession whose old workstation is owned by somebody else
     * is a different and worse problem than a released one. A newly taken ticket is released exactly
     * once; an <em>adopted</em> ticket never is, because the villager owned it before this ran.
     */
    private static OccupationTransitionResult rollback(OccupationMutator mutator, OccupationSnapshot snapshot,
                                                       @Nullable WorksiteRef site, boolean tookNewTicket,
                                                       @Nullable WorksiteRef oldSite, boolean releasedOldSite,
                                                       OccupationTransitionReason reason,
                                                       @Nullable String detail) {
        boolean clean = true;
        try {
            if (tookNewTicket && site != null) {
                mutator.releaseTicket(site);
            }
            if (releasedOldSite && oldSite != null) {
                clean = mutator.reserveTicket(oldSite);
            }
            clean &= mutator.restore(snapshot);
        } catch (Throwable t) {
            return OccupationTransitionResult.suspended(OccupationTransitionReason.ROLLBACK_INCOMPLETE,
                    detail + "; rollback also failed: " + t);
        }
        return clean
                ? OccupationTransitionResult.rejected(reason, detail)
                : OccupationTransitionResult.suspended(OccupationTransitionReason.ROLLBACK_INCOMPLETE, detail);
    }
}
