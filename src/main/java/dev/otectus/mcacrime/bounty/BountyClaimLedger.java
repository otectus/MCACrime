package dev.otectus.mcacrime.bounty;

import dev.otectus.mcacrime.ledger.Warrant;
import dev.otectus.mcacrime.state.world.BountyClaimRecord;
import dev.otectus.mcacrime.state.world.CrimeWorldData;
import dev.otectus.mcacrime.util.SafeMath;
import net.minecraft.server.MinecraftServer;

import java.util.ArrayList;
import java.util.List;
import java.util.OptionalLong;
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
        long paid = Math.max(0L, reward);
        BountyClaimRecord record = new BountyClaimRecord(key.target(), key.warrantId(), key.revision(),
                claimant, paid, now, type == null ? BountyResolutionType.KILLED : type,
                OptionalLong.of(paid));
        return data.putBountyClaimIfAbsent(key.asKey(), record);
    }

    /**
     * How much has already been paid against this warrant, across every revision of it.
     *
     * <p>The subtraction 0.6.0's payout rests on (audit finding B09). A revision does not reset the
     * price of a head; it re-opens the claim key at the new price, and the hunter is owed the
     * difference rather than the whole of it again. A claim written before 0.6.0 has no recorded
     * amount and counts as {@code currentPrice} — the conservative reading the spec asks for, which
     * costs a hunter a re-claim rather than paying an old warrant twice.
     *
     * @param currentPrice what the warrant is worth now, which is what a legacy claim is taken to have consumed
     */
    public static long alreadyPaid(CrimeWorldData data, UUID target, UUID warrantId, long currentPrice) {
        if (data == null || target == null || warrantId == null) {
            return 0L;
        }
        long total = 0L;
        for (BountyClaimRecord record : data.bountyClaims().values()) {
            if (!target.equals(record.target()) || !warrantId.equals(record.warrantId())) {
                continue;
            }
            total = SafeMath.addSat(total, record.paidAmount().orElse(Math.max(0L, currentPrice)));
        }
        return total;
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
     * @return how many were dropped, never counting one whose warrant is still open
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
            if (claimedDay(record) >= cutoff) {
                return;
            }
            // A claim is only forgettable once the warrant it was paid against is gone (0.6.0, audit
            // finding B09). Dropping one while the warrant is still on the books would hand back the
            // record of what has already been paid, and the next revision would pay it in full again.
            Warrant warrant = data.warrant(record.target());
            if (warrant != null && warrant.id().equals(record.warrantId())) {
                return;
            }
            stale.add(claimKey);
        });
        stale.forEach(data::removeBountyClaim);
        return stale.size();
    }

    /** The in-game day a claim was made. {@code claimedAt} is a game time, which is 24000 ticks a day. */
    public static long claimedDay(BountyClaimRecord record) {
        return record.claimedAt() / 24000L;
    }
}
