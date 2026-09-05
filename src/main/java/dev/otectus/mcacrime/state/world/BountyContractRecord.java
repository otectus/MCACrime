package dev.otectus.mcacrime.state.world;

import net.minecraft.nbt.CompoundTag;

import java.util.UUID;

/**
 * A posted bounty contract, as persisted (0.5.1).
 *
 * <p>The persisted form of the contract the quest bridge publishes. {@code targetName} is a plain
 * string rather than a serialised {@code Component}: the name is only ever shown, an offline target's
 * display name cannot be recomputed at load time anyway, and storing formatted JSON here would make
 * the file both larger and version-sensitive for no benefit.
 *
 * <p>Contracts are stored here rather than left to the quest mod because the quest mod is optional.
 * A contract that only existed inside MCA: Quests would vanish the moment somebody uninstalled it,
 * taking the outstanding warrant's reward with it.
 */
public record BountyContractRecord(UUID contractId, UUID target, String targetName, UUID warrantId,
                                   long warrantRevision, long principalReward, boolean killAllowed,
                                   boolean captureAllowed, long expiresAtGameTime) {

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("contractId", contractId);
        tag.putUUID("target", target);
        tag.putString("targetName", targetName == null ? "" : targetName);
        tag.putUUID("warrantId", warrantId);
        tag.putLong("warrantRevision", warrantRevision);
        tag.putLong("principalReward", principalReward);
        tag.putBoolean("killAllowed", killAllowed);
        tag.putBoolean("captureAllowed", captureAllowed);
        tag.putLong("expiresAtGameTime", expiresAtGameTime);
        return tag;
    }

    /** Throws on a tag missing the contract, target or warrant id; the caller skips that one entry. */
    public static BountyContractRecord load(CompoundTag tag) {
        return new BountyContractRecord(
                tag.getUUID("contractId"),
                tag.getUUID("target"),
                tag.getString("targetName"),
                tag.getUUID("warrantId"),
                tag.getLong("warrantRevision"),
                tag.getLong("principalReward"),
                tag.getBoolean("killAllowed"),
                tag.getBoolean("captureAllowed"),
                tag.getLong("expiresAtGameTime"));
    }
}
