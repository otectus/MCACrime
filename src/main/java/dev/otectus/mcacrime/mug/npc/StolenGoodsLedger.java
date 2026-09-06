package dev.otectus.mcacrime.mug.npc;

import dev.otectus.mcacrime.McaCrimeConfig;
import dev.otectus.mcacrime.state.world.CrimeWorldData;
import dev.otectus.mcacrime.state.world.PropertyLot;
import dev.otectus.mcacrime.state.world.ServerMutationGate;
import dev.otectus.mcacrime.state.world.StolenGoodsRecord;
import dev.otectus.mcacrime.util.CrimeDebug;
import net.minecraft.server.MinecraftServer;

import javax.annotation.Nullable;
import java.nio.charset.StandardCharsets;
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
                : record(CrimeWorldData.get(server), transactionId, thief, owner, result, now);
    }

    @Nullable
    public static UUID record(CrimeWorldData data, UUID transactionId, UUID thief, UUID owner,
                              TheftExecutor.TheftResult result, long now) {
        if (data == null || transactionId == null || thief == null || owner == null
                || result == null || !result.tookSomething()) {
            return null;
        }
        if (!ServerMutationGate.allows(data)) {
            return null;
        }
        // The provenance row is what makes the theft recoverable, so a table with no room for it is a
        // theft that must not happen. Refusing here is refusing before anything leaves an inventory.
        if (!data.putStolenGoods(StolenGoodsRecord.ofStack(transactionId, thief, owner, result.stack(),
                result.currency(), now)).stored()) {
            return null;
        }
        return transactionId;
    }

    // ------------------------------------------------------------------ claiming

    /**
     * Where a claimed record actually goes.
     *
     * <p>A claim used to be a removal and nothing else: the caller was handed the records and was
     * trusted to get them to somebody. When it could not -- the owner offline, out of slots, in another
     * dimension -- the row was gone and so was the property. This says whether delivery happened, and
     * the answer decides between forgetting the row and turning it into a {@link PropertyLot}.
     */
    @FunctionalInterface
    public interface Delivery {
        /** @return true only when the whole record reached somebody who can keep it */
        boolean deliver(StolenGoodsRecord record);
    }

    /** Everything this thief is holding, removed from the ledger in the same call. */
    public static List<StolenGoodsRecord> claimAll(MinecraftServer server, UUID thief) {
        return server == null ? List.of() : claimAll(CrimeWorldData.get(server), thief);
    }

    /**
     * The caller takes responsibility for delivery.
     *
     * <p>Kept for the paths that hand records straight into something that cannot fail to accept them
     * -- a drop list on a corpse, most of all. Anything that gives to a player should use the
     * {@link Delivery} overload, so an owner who cannot take it now still gets it later.
     */
    public static List<StolenGoodsRecord> claimAll(CrimeWorldData data, UUID thief) {
        return claim(data, thief, null, record -> true, 0L);
    }

    /** Everything this thief is holding, delivered through {@code delivery} and escrowed if it fails. */
    public static List<StolenGoodsRecord> claimAll(CrimeWorldData data, UUID thief, Delivery delivery,
                                                   long now) {
        return claim(data, thief, null, delivery, now);
    }

    /** The subset of this thief's holdings that belong to one owner, removed as it is returned. */
    public static List<StolenGoodsRecord> claimForOwner(MinecraftServer server, UUID thief, UUID owner) {
        return server == null ? List.of() : claimForOwner(CrimeWorldData.get(server), thief, owner);
    }

    public static List<StolenGoodsRecord> claimForOwner(CrimeWorldData data, UUID thief, UUID owner) {
        return owner == null ? List.of() : claim(data, thief, owner, record -> true, 0L);
    }

    /** One owner's property, delivered through {@code delivery} and escrowed if it fails. */
    public static List<StolenGoodsRecord> claimForOwner(CrimeWorldData data, UUID thief, UUID owner,
                                                        Delivery delivery, long now) {
        return owner == null ? List.of() : claim(data, thief, owner, delivery, now);
    }

    /**
     * The one place entries leave the ledger. {@code owner} null claims everything.
     *
     * <p>The removal happens per entry through {@code removeStolenGoods}, which keeps the by-thief
     * index in step and sets the data dirty; only entries that were genuinely removed are returned, so
     * two callers racing each get a disjoint half of the loot rather than a copy of all of it.
     */
    private static List<StolenGoodsRecord> claim(CrimeWorldData data, UUID thief, @Nullable UUID owner,
                                                 Delivery delivery, long now) {
        if (data == null || thief == null || delivery == null || !ServerMutationGate.allows(data)) {
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
            if (delivery.deliver(record)) {
                StolenGoodsRecord removed = data.removeStolenGoods(record.transactionId());
                if (removed != null) {
                    claimed.add(removed);
                }
                continue;
            }
            // Undeliverable. The row still leaves the ledger -- the thief is not holding it any more --
            // but only because it becomes a lot that says the same thing and can be handed over later.
            // If the escrow will not take it either, the row stays exactly where it is: nothing is
            // allowed to remove the last record of somebody's property.
            if (escrow(data, record, now)) {
                data.removeStolenGoods(record.transactionId());
            }
        }
        return List.copyOf(claimed);
    }

    /** Turns one record into a lot owed to its owner. False when the escrow had no room. */
    private static boolean escrow(CrimeWorldData data, StolenGoodsRecord record, long now) {
        // Derived from the transaction id rather than random, so replaying the same claim twice
        // produces the same lot id and cannot mint a second copy of the property.
        UUID lotId = UUID.nameUUIDFromBytes(("lot:" + record.transactionId())
                .getBytes(StandardCharsets.UTF_8));
        PropertyLot lot = new PropertyLot(lotId, record.owner(),
                record.hasStack() ? record.stackTag() : null, record.currency(), "",
                record.transactionId(), PropertyLot.DeliveryState.PENDING, now);
        return data.putPropertyLot(lot).stored();
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
            // "Laundered" is investigation metadata, not a licence to delete. The trail goes cold --
            // nobody is coming to reclaim this at the scene any more -- but the property itself is still
            // somebody's, so it moves to escrow rather than out of existence.
            if (!escrow(data, record, today * TICKS_PER_DAY)) {
                continue;
            }
            if (data.removeStolenGoods(record.transactionId()) != null) {
                expired++;
            }
        }
        if (expired > 0) {
            CrimeDebug.crime("stolen goods laundered: {} record(s) older than {} day(s) moved to escrow",
                    expired, persistenceDays);
        }
        return expired;
    }
}
