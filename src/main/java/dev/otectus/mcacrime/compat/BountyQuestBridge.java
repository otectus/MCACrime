package dev.otectus.mcacrime.compat;

import dev.otectus.mcacrime.bounty.BountyContract;
import dev.otectus.mcacrime.bounty.BountyResolution;

import java.util.UUID;

/**
 * Everything common code is allowed to know about a quest mod (0.5.1).
 *
 * <p>The spec's rule for this seam is a dependency direction, not a feature: MCA: Crime owns the
 * warrant, the price, the legality of the resolution and the payout ledger; a quest mod owns
 * presenting one of those as something a player can accept, and nothing else. So the interface is
 * one-way and lossy on purpose — a bridge is told what happened and is never asked whether it may
 * happen, which is what makes "do not let both systems award the principal bounty" structurally true
 * rather than a rule somebody has to remember.
 *
 * <p>{@link #NOOP} is the implementation that runs whenever the quest mod is absent, disabled or
 * broken, so no call site anywhere needs a null check or a "is the integration on" branch.
 */
public interface BountyQuestBridge {

    /** Whether a live quest integration is behind this bridge. */
    boolean available();

    /** A contract was posted. The bridge may present it; the reward is not its to pay. */
    void publish(BountyContract contract);

    /** A bounty was claimed and paid. Fired once per claim key, ever. */
    void onBountyResolved(BountyResolution resolution);

    /** A contract stopped being collectable — the warrant closed, or the target went lawful. */
    void invalidate(UUID contractId);

    /** The bridge used when no quest mod is answering. Every method is deliberately silent. */
    BountyQuestBridge NOOP = new BountyQuestBridge() {

        @Override
        public boolean available() {
            return false;
        }

        @Override
        public void publish(BountyContract contract) {
        }

        @Override
        public void onBountyResolved(BountyResolution resolution) {
        }

        @Override
        public void invalidate(UUID contractId) {
        }
    };
}
