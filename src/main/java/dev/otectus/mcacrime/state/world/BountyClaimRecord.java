package dev.otectus.mcacrime.state.world;

import dev.otectus.mcacrime.bounty.BountyResolutionType;
import net.minecraft.nbt.CompoundTag;

import java.util.UUID;

/**
 * A bounty that has already been paid (0.5.1). Persisted so it can never be paid again.
 *
 * <p>The key is {@code (target, warrantId, revision)}, and every part of it is load-bearing. Without
 * the warrant id, a target who becomes Wanted a second time collides with the first. Without the
 * revision, dying, respawning and re-offending would re-open the same key. Storing the claim rather
 * than a bare "already paid" flag also means an operator can see who was paid what and when.
 */
public record BountyClaimRecord(UUID target, UUID warrantId, long revision, UUID claimant, long reward,
                                long claimedAt, BountyResolutionType type) {

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("target", target);
        tag.putUUID("warrantId", warrantId);
        tag.putLong("revision", revision);
        tag.putUUID("claimant", claimant);
        tag.putLong("reward", reward);
        tag.putLong("claimedAt", claimedAt);
        tag.putString("type", type.name());
        return tag;
    }

    /** Throws on a tag missing any of the three key ids; the caller skips that one entry. */
    public static BountyClaimRecord load(CompoundTag tag) {
        return new BountyClaimRecord(
                tag.getUUID("target"),
                tag.getUUID("warrantId"),
                tag.getLong("revision"),
                tag.getUUID("claimant"),
                tag.getLong("reward"),
                tag.getLong("claimedAt"),
                BountyResolutionType.parse(tag.getString("type")));
    }
}
