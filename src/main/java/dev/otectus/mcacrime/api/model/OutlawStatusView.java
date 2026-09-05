package dev.otectus.mcacrime.api.model;

import dev.otectus.mcacrime.crime.Band;

/**
 * A companion mod's view of one player's legal standing (0.5.1).
 *
 * <p>Mirrors the internal {@code OutlawStatus} minus the parts that are ours: the warrant id and
 * revision are the key a bounty claim is deduplicated on, and handing them across the API boundary
 * would invite a companion to construct a claim key itself. {@code basis} is exposed as its lang key
 * rather than as our enum, so adding a basis later is not a breaking change for anybody.
 */
public record OutlawStatusView(boolean lawfulCombatTarget, boolean lethalForceLawful, boolean bountyEligible,
                               String basisKey, long heat, long karma, Band band) {
}
