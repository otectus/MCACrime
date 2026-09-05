package dev.otectus.mcacrime.mug.npc;

import dev.otectus.mcacrime.McaCrimeConfig;
import dev.otectus.mcacrime.state.world.CrimeWorldData;
import dev.otectus.mcacrime.state.world.StolenGoodsRecord;
import dev.otectus.mcacrime.util.CrimeDebug;
import net.minecraft.core.HolderLookup;
import net.minecraft.server.MinecraftServer;

import org.jetbrains.annotations.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * The FINALIZE half of the theft transaction, and the only thing that knows a thief is carrying
 * somebody else's property (spec §"Stolen-goods recovery").
 *
 * <p>Everything here is a claim rather than a read. {@link #claimAll} removes the entries it returns
 * and marks the world data dirty <em>before</em> handing them back, so a second death event, a crash
 * between the drop and the save, or two recovery paths racing can never produce the same sword twice.
 * That ordering is the whole anti-duplication mechanism; a caller that wants to look without taking
 * should ask {@code CrimeWorldData.stolenGoodsByThief} instead.
 *
 * <p>Every method has a {@link CrimeWorldData} overload beside the {@link MinecraftServer} one. The
 * server overload is what callers use; the data overload is what makes the claim-once property
 * testable, since world data can be built without a server and a server cannot.
 */
public final class StolenGoodsLedger {

    /** Ticks in a Minecraft day, the unit {@code stolenGoodsPersistenceDays} is expressed in. */
    private static final long TICKS_PER_DAY = 24000L;

    private StolenGoodsLedger() {
    }

    // ------------------------------------------------------------------ recording

    /**
     * Files what a mugging took.
     *
     * @param transactionId the mug session's id, reused so the ledger entry, the attempt events and
     *                      the victim's HUD all name the same transaction
     * @return the transaction id, or null when the theft took nothing and there is nothing to file
     */
    @Nullable
    public static UUID record(MinecraftServer server, UUID transactionId, UUID thief, UUID owner,
                              TheftExecutor.TheftResult result, long now) {
        return server == null ? null
                : record(CrimeWorldData.get(server), server.registryAccess(), transactionId, thief, owner,
                        result, now);
    }

    /**
     * @param provider the registries the stolen stack is snapshotted against; in 1.21 an item's
     *                 components hold registry references, so encoding one needs a lookup and the
     *                 server's own {@code registryAccess()} is the only correct answer
     */
    @Nullable
    public static UUID record(CrimeWorldData data, HolderLookup.Provider provider, UUID transactionId,
                              UUID thief, UUID owner, TheftExecutor.TheftResult result, long now) {
        if (data == null || provider == null || transactionId == null || thief == null || owner == null
                || result == null || !result.tookSomething()) {
            return null;
        }
        data.putStolenGoods(StolenGoodsRecord.ofStack(provider, transactionId, thief, owner, result.stack(),
                result.currency(), now));
        return transactionId;
    }

    // ------------------------------------------------------------------ claiming

    /** Everything this thief is holding, removed from the ledger in the same call. */
    public static List<StolenGoodsRecord> claimAll(MinecraftServer server, UUID thief) {
        return server == null ? List.of() : claimAll(CrimeWorldData.get(server), thief);
    }

    public static List<StolenGoodsRecord> claimAll(CrimeWorldData data, UUID thief) {
        return claim(data, thief, null);
    }

    /** The subset of this thief's holdings that belong to one owner, removed as it is returned. */
    public static List<StolenGoodsRecord> claimForOwner(MinecraftServer server, UUID thief, UUID owner) {
        return server == null ? List.of() : claimForOwner(CrimeWorldData.get(server), thief, owner);
    }

    public static List<StolenGoodsRecord> claimForOwner(CrimeWorldData data, UUID thief, UUID owner) {
        return owner == null ? List.of() : claim(data, thief, owner);
    }

    /**
     * The one place entries leave the ledger. {@code owner} null claims everything.
     *
     * <p>The removal happens per entry through {@code removeStolenGoods}, which keeps the by-thief
     * index in step and sets the data dirty; only entries that were genuinely removed are returned, so
     * two callers racing each get a disjoint half of the loot rather than a copy of all of it.
     */
    private static List<StolenGoodsRecord> claim(CrimeWorldData data, UUID thief, @Nullable UUID owner) {
        if (data == null || thief == null) {
            return List.of();
        }
        List<StolenGoodsRecord> held = data.stolenGoodsByThief(thief);
        if (held.isEmpty()) {
            return List.of();
        }
        List<StolenGoodsRecord> claimed = new ArrayList<>(held.size());
        for (StolenGoodsRecord record : held) {
            if (owner != null && !owner.equals(record.owner())) {
                continue;
            }
            StolenGoodsRecord removed = data.removeStolenGoods(record.transactionId());
            if (removed != null) {
                claimed.add(removed);
            }
        }
        return List.copyOf(claimed);
    }

    // ------------------------------------------------------------------ expiry

    /**
     * Launders thefts old enough that nobody is coming back for them, so world data does not grow
     * without bound.
     *
     * @param activeThieves thieves currently mugging or escaping; the spec forbids expiring property
     *                      out from under a theft that is still happening
     */
    public static int expire(MinecraftServer server, long today, Set<UUID> activeThieves) {
        if (server == null) {
            return 0;
        }
        return expire(CrimeWorldData.get(server), today,
                McaCrimeConfig.COMMON.thiefStolenGoodsPersistenceDays.get(), activeThieves);
    }

    /** @param persistenceDays 0 disables expiry entirely; stolen goods are then kept forever */
    public static int expire(CrimeWorldData data, long today, int persistenceDays, Set<UUID> activeThieves) {
        if (data == null || persistenceDays <= 0) {
            return 0;
        }
        int expired = 0;
        for (StolenGoodsRecord record : List.copyOf(data.stolenGoods())) {
            if (activeThieves != null && activeThieves.contains(record.thief())) {
                continue;
            }
            if (today - record.stolenAt() / TICKS_PER_DAY < persistenceDays) {
                continue;
            }
            if (data.removeStolenGoods(record.transactionId()) != null) {
                expired++;
            }
        }
        if (expired > 0) {
            CrimeDebug.crime("stolen goods laundered: {} record(s) older than {} day(s)", expired, persistenceDays);
        }
        return expired;
    }
}
