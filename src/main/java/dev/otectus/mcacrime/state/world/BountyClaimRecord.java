package dev.otectus.mcacrime.state.world;

import dev.otectus.mcacrime.bounty.BountyResolutionType;
import net.minecraft.nbt.CompoundTag;

import java.util.OptionalLong;
import java.util.UUID;

/**
 * A bounty that has already been paid (0.5.1). Persisted so it can never be paid again.
 *
 * <p>The key is {@code (target, warrantId, revision)}, and every part of it is load-bearing. Without
 * the warrant id, a target who becomes Wanted a second time collides with the first. Without the
 * revision, dying, respawning and re-offending would re-open the same key. Storing the claim rather
 * than a bare "already paid" flag also means an operator can see who was paid what and when.
 *
 * <p>{@code paidAmount} is 0.6.0's addition (audit finding B09) and it is an {@link OptionalLong}
 * rather than a {@code long} because the difference between "nothing was paid" and "nobody wrote it
 * down" decides money. A warrant that gains a revision mints a new key, so the same warrant can be
 * claimed again at the new price; what stops that being a second full payout is subtracting what has
 * already been paid against the same warrant. A 0.5.1 claim has no figure to subtract, and the spec's
 * instruction (§9.2) is to consume conservatively: an absent amount counts as the whole of the
 * current price, so a legacy claim pays nothing further rather than paying twice.
 */
public record BountyClaimRecord(UUID target, UUID warrantId, long revision, UUID claimant, long reward,
                                long claimedAt, BountyResolutionType type, OptionalLong paidAmount) {

    /** A claim in the shape 0.5.1 wrote: no recorded amount, and therefore conservatively consumed. */
    public BountyClaimRecord(UUID target, UUID warrantId, long revision, UUID claimant, long reward,
                             long claimedAt, BountyResolutionType type) {
        this(target, warrantId, revision, claimant, reward, claimedAt, type, OptionalLong.empty());
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("target", target);
        tag.putUUID("warrantId", warrantId);
        tag.putLong("revision", revision);
        tag.putUUID("claimant", claimant);
        tag.putLong("reward", reward);
        tag.putLong("claimedAt", claimedAt);
        tag.putString("type", type.name());
        paidAmount.ifPresent(paid -> tag.putLong("paidAmount", paid));
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
                BountyResolutionType.parse(tag.getString("type")),
                tag.contains("paidAmount")
                        ? OptionalLong.of(tag.getLong("paidAmount"))
                        : OptionalLong.empty());
    }
}
