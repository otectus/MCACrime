package dev.otectus.mcacrime.state.world;

import dev.otectus.mcacrime.McaCrimeConfig;
import dev.otectus.mcacrime.ai.thief.ThiefBehaviorService;
import dev.otectus.mcacrime.bounty.BountyClaimLedger;
import dev.otectus.mcacrime.job.CriminalJob;
import dev.otectus.mcacrime.ledger.Warrant;
import dev.otectus.mcacrime.mug.npc.StolenGoodsLedger;
import dev.otectus.mcacrime.util.CrimeDebug;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The pass that keeps world data from growing forever (0.5.1, spec §"Bounded persistence").
 *
 * <p>Every collection this mod added in 0.5.1 grows with play and shrinks with nothing: a criminal
 * villager killed by a creeper leaves a record nobody will ever look at again, a theft nobody came
 * back for keeps its provenance, a paid claim outlives the warrant it was keyed on, a contract
 * outlives the warrant it was posted against, and a fence who was demoted to a farmer keeps a restock
 * day. None of that is a leak in a session; all of it is a file that is written on every autosave.
 *
 * <p>Two rules shape what may be forgotten. Nothing is dropped while it is still in play — an active
 * thief's holdings survive regardless of age, and a criminal record survives while its villager is
 * loaded, because "stale" is a claim about the world and an unloaded chunk is not evidence. And
 * claims are dropped last and latest, because forgetting one early is the only mistake here that pays
 * somebody twice.
 *
 * <p>{@link #sweep(CrimeWorldData, long, long, Set, Set, Policy)} is pure so those rules can be tested
 * without a server; {@link #tick(MinecraftServer)} is the wiring that reads the world and supplies the
 * two "still in play" sets.
 */
public final class CrimeMaintenanceSweep {

    /** Game ticks between passes. Five in-game minutes: nothing here is urgent, and none of it is free. */
    public static final int INTERVAL_TICKS = 6000;

    private static long lastRunAt = Long.MIN_VALUE;

    private CrimeMaintenanceSweep() {
    }

    /** The retention keys the sweep reads, snapshotted so the policy can be tested without a config. */
    public record Policy(int staleRecordGraceDays, int stolenGoodsPersistenceDays, int claimRetentionDays) {

        public static Policy fromConfig() {
            McaCrimeConfig.Common c = McaCrimeConfig.COMMON;
            return new Policy(c.staleRecordGraceDays.get(), c.thiefStolenGoodsPersistenceDays.get(),
                    c.claimRetentionDays.get());
        }
    }

    /** What one pass dropped. Reported to the debug log, and asserted on in tests. */
    public record Result(int stolenGoods, int criminals, int claims, int contracts, int restockDays) {

        public boolean isEmpty() {
            return stolenGoods == 0 && criminals == 0 && claims == 0 && contracts == 0 && restockDays == 0;
        }
    }

    /** Runs the pass if enough time has passed since the last one. Called from the guard scan. */
    public static void tick(MinecraftServer server) {
        if (server == null) {
            return;
        }
        long gameTime = server.overworld().getGameTime();
        if (lastRunAt != Long.MIN_VALUE && gameTime - lastRunAt < INTERVAL_TICKS) {
            return;
        }
        lastRunAt = gameTime;

        Result result = sweep(CrimeWorldData.get(server), gameTime / 24000L, gameTime,
                activeThieves(), loadedEntities(server), Policy.fromConfig());
        if (!result.isEmpty()) {
            CrimeDebug.crime("maintenance sweep: {} stolen record(s), {} criminal(s), {} claim(s), "
                            + "{} contract(s), {} restock day(s) dropped", result.stolenGoods(),
                    result.criminals(), result.claims(), result.contracts(), result.restockDays());
        }
    }

    /**
     * One maintenance pass.
     *
     * @param today         the current in-game day
     * @param gameTime      the current game time, for contracts with a clock
     * @param activeThieves thieves mid-mug or mid-escape; their holdings are never expired
     * @param loaded        entity ids currently loaded; a criminal among them is not stale whatever
     *                      their last-seen day says
     */
    public static Result sweep(CrimeWorldData data, long today, long gameTime, Set<UUID> activeThieves,
                               Set<UUID> loaded, Policy policy) {
        if (data == null || policy == null) {
            return new Result(0, 0, 0, 0, 0);
        }
        int stolen = StolenGoodsLedger.expire(data, today, policy.stolenGoodsPersistenceDays(), activeThieves);
        int criminals = expireCriminals(data, today, loaded, policy.staleRecordGraceDays());
        int claims = BountyClaimLedger.expire(data, today, policy.claimRetentionDays());
        int contracts = expireContracts(data, gameTime);
        int restock = expireRestockDays(data);
        // Finished receipts only, and only past the retention window. Not reported in the result: a
        // receipt ageing out is bookkeeping, not something that happened in the world.
        data.pruneTransactions(gameTime);
        return new Result(stolen, criminals, claims, contracts, restock);
    }

    /**
     * Forgets criminal villagers who have not been seen for {@code graceDays} and are not loaded now.
     *
     * <p>The grace period is a config key rather than a constant because the honest value depends on
     * how a server plays: a village nobody visits for a month is not a village whose thief has ceased
     * to exist, and the only way to be sure would be to load the chunk, which is exactly what this
     * pass must not do.
     */
    private static int expireCriminals(CrimeWorldData data, long today, Set<UUID> loaded, int graceDays) {
        if (graceDays <= 0) {
            return 0;
        }
        int removed = 0;
        for (CriminalVillagerRecord record : List.copyOf(data.criminalVillagers())) {
            if (loaded != null && loaded.contains(record.villager())) {
                continue;
            }
            if (today - record.lastSeenDay() < graceDays) {
                continue;
            }
            data.removeCriminalVillager(record.villager());
            removed++;
        }
        return removed;
    }

    /** Drops contracts whose warrant has closed, moved on a revision, or vanished — and expired ones. */
    private static int expireContracts(CrimeWorldData data, long gameTime) {
        int removed = 0;
        for (BountyContractRecord record : data.bountyContracts()) {
            Warrant warrant = data.warrant(record.target());
            boolean stale = warrant == null
                    || !warrant.open()
                    || !warrant.id().equals(record.warrantId())
                    || warrant.revision() != record.warrantRevision();
            boolean expired = record.expiresAtGameTime() > 0L && gameTime >= record.expiresAtGameTime();
            if (stale || expired) {
                data.removeBountyContract(record.contractId());
                removed++;
            }
        }
        return removed;
    }

    /** A restock day belongs to a fence. Anybody else's is a leftover from a job that changed. */
    private static int expireRestockDays(CrimeWorldData data) {
        int removed = 0;
        for (Map.Entry<UUID, Long> entry : data.fenceRestockDays().entrySet()) {
            CriminalVillagerRecord record = data.criminalVillager(entry.getKey());
            if (record == null || record.job() != CriminalJob.FENCE) {
                data.removeFenceRestockDay(entry.getKey());
                removed++;
            }
        }
        // Stock counts belong to a fence for the same reason, and they are the table with a cap on it
        // (0.6.0): a village whose fences keep changing job would otherwise fill it with the counts of
        // villagers who no longer sell anything, and a full table is a fence that cannot open.
        for (UUID fence : List.copyOf(data.fenceStockIds())) {
            CriminalVillagerRecord record = data.criminalVillager(fence);
            if (record == null || record.job() != CriminalJob.FENCE) {
                data.removeFenceStock(fence);
                removed++;
            }
        }
        return removed;
    }

    private static Set<UUID> activeThieves() {
        Set<UUID> active = new HashSet<>();
        ThiefBehaviorService.snapshot().forEach(controller -> active.add(controller.thiefId()));
        return active;
    }

    /** Every entity id loaded anywhere right now. Cheap: this runs once every five in-game minutes. */
    private static Set<UUID> loadedEntities(MinecraftServer server) {
        Set<UUID> loaded = new HashSet<>();
        for (ServerLevel level : server.getAllLevels()) {
            for (Entity entity : level.getAllEntities()) {
                loaded.add(entity.getUUID());
            }
        }
        return loaded;
    }

    /** Drops the throttle so a second world in one session starts clean. */
    public static void clearAll() {
        lastRunAt = Long.MIN_VALUE;
    }
}
