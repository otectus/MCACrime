package dev.otectus.mcacrime.bounty;

import dev.otectus.mcacrime.state.world.BountyContractRecord;

import java.util.UUID;

/**
 * One posted bounty, as the rest of the mod passes it around (0.5.1).
 *
 * <p>The in-memory twin of {@link BountyContractRecord}: same fields, no NBT. Two types rather than
 * one because the persisted form belongs to {@code state.world} — where every record is a save/load
 * pair and nothing else — while this is the thing a quest bridge is handed, and the seam between the
 * two is worth being able to see.
 *
 * <p>{@code targetName} is a plain string for the reason the persisted record gives: an offline
 * target's display name cannot be recomputed, so the name is captured when the contract is posted and
 * turned into a {@code Component} only at display time.
 *
 * <p>A contract is identified by {@link #contractId()} but <em>keyed</em> on
 * {@code (target, warrantId, revision)} — see {@link #claimKey()}. That is the same identity a bounty
 * claim uses, which is what lets a resolution invalidate exactly the contract it paid for.
 */
public record BountyContract(UUID contractId, UUID targetUuid, String targetName, UUID warrantId,
                             long warrantRevision, long principalReward, boolean killAllowed,
                             boolean captureAllowed, long expiresAtGameTime) {

    /** What paying this contract would claim. Equal keys mean the same posting. */
    public BountyClaimKey claimKey() {
        return new BountyClaimKey(targetUuid, warrantId, warrantRevision);
    }

    /**
     * Whether this contract has run out its clock.
     *
     * <p>{@code expiresAtGameTime} of zero means "no clock": in 0.5.1 a contract ends when its warrant
     * closes or is revised, not on a timer, and the maintenance sweep is what notices. The field is
     * honoured anyway so a future expiry policy needs no migration.
     */
    public boolean expired(long gameTime) {
        return expiresAtGameTime > 0L && gameTime >= expiresAtGameTime;
    }

    public BountyContractRecord toRecord() {
        return new BountyContractRecord(contractId, targetUuid, targetName, warrantId, warrantRevision,
                principalReward, killAllowed, captureAllowed, expiresAtGameTime);
    }

    public static BountyContract from(BountyContractRecord record) {
        return new BountyContract(record.contractId(), record.target(), record.targetName(),
                record.warrantId(), record.warrantRevision(), record.principalReward(),
                record.killAllowed(), record.captureAllowed(), record.expiresAtGameTime());
    }
}
