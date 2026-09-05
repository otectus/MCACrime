package dev.otectus.mcacrime.bounty;

import dev.otectus.mcacrime.state.world.BountyClaimRecord;
import dev.otectus.mcacrime.state.world.CrimeWorldData;
import net.minecraft.server.MinecraftServer;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The one place a bounty may be marked paid (0.5.1).
 *
 * <p>Everything anti-exploit about bounties lives behind {@link #tryClaim}, and it is a single call
 * rather than a check followed by a write because two resolutions landing in the same tick must not
 * both find the key absent. {@code CrimeWorldData.putBountyClaimIfAbsent} is the atomic primitive; the
 * caller pays only when this returns true, so the claim is marked <em>before</em> any currency moves.
 * The spec asks for "atomically mark paid before or with currency issuance"; this is the "before".
 *
 * <p>Claims are persisted in world data, not in memory, so a reconnect, a restart, or a server crash
 * between the mark and the payout can only ever cost the hunter money — never pay twice.
 */
public final class BountyClaimLedger {

    private BountyClaimLedger() {
    }

    /**
     * Marks {@code key} claimed by {@code claimant}, if it has never been claimed before.
     *
     * <p>Refuses a self-claim outright. A target who arranges their own death — through an alt, a
     * friend paid off-band, or a second account — is the cheapest farm there is, and it costs one
     * comparison to close.
     *
     * @return true when this call is the one that claimed it, and therefore the one that should pay
     */
    public static boolean tryClaim(MinecraftServer server, BountyClaimKey key, UUID claimant,
                                   long reward, long now, BountyResolutionType type) {
        return server != null && tryClaim(CrimeWorldData.get(server), key, claimant, reward, now, type);
    }

    /**
     * {@link #tryClaim(MinecraftServer, BountyClaimKey, UUID, long, long, BountyResolutionType)}
     * against world data directly. The exploit rules are worth testing without a server to test them on.
     */
    public static boolean tryClaim(CrimeWorldData data, BountyClaimKey key, UUID claimant,
                                   long reward, long now, BountyResolutionType type) {
        if (data == null || key == null || claimant == null || claimant.equals(key.target())) {
            return false;
        }
        BountyClaimRecord record = new BountyClaimRecord(key.target(), key.warrantId(), key.revision(),
                claimant, Math.max(0L, reward), now, type == null ? BountyResolutionType.KILLED : type);
        return data.putBountyClaimIfAbsent(key.asKey(), record);
    }

    /** Whether this exact warrant revision has already been paid for. */
    public static boolean isClaimed(MinecraftServer server, BountyClaimKey key) {
        return server != null && key != null && CrimeWorldData.get(server).bountyClaim(key.asKey()) != null;
    }

    /**
     * Drops claims older than {@code retentionDays}, measured in in-game days.
     *
     * <p>Claims are the only collection here that grows monotonically with play, one entry per bounty
     * ever paid, in a file written on every autosave. Forgetting an ancient one is safe because the
     * warrant it names is long closed and its revision can never come round again: a re-offence mints
     * a new warrant id.
     *
     * @param today the current in-game day
     * @return how many were dropped
     */
    public static int expire(MinecraftServer server, long today, int retentionDays) {
        return server == null ? 0 : expire(CrimeWorldData.get(server), today, retentionDays);
    }

    /** {@link #expire(MinecraftServer, long, int)} against world data directly. */
    public static int expire(CrimeWorldData data, long today, int retentionDays) {
        if (data == null || retentionDays <= 0) {
            return 0;
        }
        long cutoff = today - retentionDays;
        if (cutoff <= 0L) {
            return 0;
        }
        List<String> stale = new ArrayList<>();
        data.bountyClaims().forEach((claimKey, record) -> {
            if (claimedDay(record) < cutoff) {
                stale.add(claimKey);
            }
        });
        stale.forEach(data::removeBountyClaim);
        return stale.size();
    }

    /** The in-game day a claim was made. {@code claimedAt} is a game time, which is 24000 ticks a day. */
    public static long claimedDay(BountyClaimRecord record) {
        return record.claimedAt() / 24000L;
    }
}
