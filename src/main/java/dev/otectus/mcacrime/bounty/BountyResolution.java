package dev.otectus.mcacrime.bounty;

import java.util.UUID;

/**
 * One bounty, resolved (0.5.1): who was worth what to whom, and how it ended.
 *
 * <p>Spec §"Do not accidentally double-reward combat events" asks for exactly this object rather than
 * a pile of parameters on an event, and the reason is the contract bridge that follows in the next
 * release: a quest that completes on "the player collected a bounty" has to be able to tell whether
 * the bounty it is watching is the one that just resolved. The claim key is that identity, so it
 * travels with the payment.
 */
public record BountyResolution(BountyClaimKey claimKey, UUID target, UUID claimant, long principalReward,
                               BountyResolutionType resolutionType) {
}
