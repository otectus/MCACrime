package dev.otectus.mcacrime.bounty;

import dev.otectus.mcacrime.ledger.Warrant;

import java.util.UUID;

/**
 * What a bounty is paid <em>against</em> (0.5.1): one target, one warrant, one revision of it.
 *
 * <p>All three parts earn their place. Drop the target and two offenders share a key. Drop the warrant
 * id and a player who becomes Wanted a second time collides with the record of the first. Drop the
 * revision and dying, respawning and re-offending re-opens a key that was already paid — which is the
 * exact farm the spec names.
 *
 * <p>{@link #asKey()} is the persisted form, and it is a string rather than the record itself because
 * NBT has no map-with-record-key: {@code CrimeWorldData.bountyClaims} is keyed on it.
 */
public record BountyClaimKey(UUID target, UUID warrantId, long revision) {

    /** The claim key for the current state of {@code warrant}. */
    public static BountyClaimKey of(Warrant warrant) {
        return new BountyClaimKey(warrant.offender(), warrant.id(), warrant.revision());
    }

    /** The persisted map key. Stable across restarts, which is what makes a claim outlive a reconnect. */
    public String asKey() {
        return target + "/" + warrantId + "/" + revision;
    }
}
