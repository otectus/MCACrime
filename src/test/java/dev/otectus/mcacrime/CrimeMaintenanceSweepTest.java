package dev.otectus.mcacrime;

import dev.otectus.mcacrime.bounty.BountyContractBoard;
import dev.otectus.mcacrime.job.CriminalJob;
import dev.otectus.mcacrime.ledger.Warrant;
import dev.otectus.mcacrime.state.world.CrimeMaintenanceSweep;
import dev.otectus.mcacrime.state.world.CriminalVillagerRecord;
import dev.otectus.mcacrime.state.world.CrimeWorldData;
import dev.otectus.mcacrime.state.world.StolenGoodsRecord;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The housekeeping pass, and the two things it must never do.
 *
 * <p>It must not take property out of a theft that is still happening: an active thief's holdings are
 * kept whatever their age, because the whole point of the provenance ledger is that the victim can get
 * them back when the thief is caught. And it must not decide a villager has ceased to exist because
 * nobody has walked past them lately: the grace period is a floor, and a loaded villager is never
 * stale regardless of what their last-seen day says.
 *
 * <p>Everything here runs against the pure {@link CrimeMaintenanceSweep#sweep} core over world data
 * built without a server, which is why the policy is a parameter rather than a config read.
 */
class CrimeMaintenanceSweepTest {

    private static final UUID THIEF = UUID.fromString("00000000-0000-0000-0000-0000000000e1");
    private static final UUID IDLE_THIEF = UUID.fromString("00000000-0000-0000-0000-0000000000e2");
    private static final UUID OWNER = UUID.fromString("00000000-0000-0000-0000-0000000000f1");
    private static final UUID FENCE = UUID.fromString("00000000-0000-0000-0000-0000000000f2");
    private static final CrimeMaintenanceSweep.Policy POLICY =
            new CrimeMaintenanceSweep.Policy(14, 7, 30);

    private static final long TICKS_PER_DAY = 24000L;

    private static void criminal(CrimeWorldData data, UUID villager, CriminalJob job, long lastSeenDay) {
        data.putCriminalVillager(new CriminalVillagerRecord(villager, job, 0L, 0L, lastSeenDay,
                false, 1L, null));
    }

    @Test
    void anActiveThiefKeepsWhatTheyStoleHoweverOldItIs() {
        CrimeWorldData data = new CrimeWorldData();
        data.putStolenGoods(new StolenGoodsRecord(UUID.randomUUID(), THIEF, OWNER, null, 12L, 0L));
        data.putStolenGoods(new StolenGoodsRecord(UUID.randomUUID(), IDLE_THIEF, OWNER, null, 12L, 0L));

        CrimeMaintenanceSweep.Result result = CrimeMaintenanceSweep.sweep(data, 40L, 40L * TICKS_PER_DAY,
                Set.of(THIEF), Set.of(), POLICY);

        assertEquals(1, result.stolenGoods(), "only the idle thief's holdings should have expired");
        assertEquals(1, data.stolenGoodsByThief(THIEF).size());
        assertTrue(data.stolenGoodsByThief(IDLE_THIEF).isEmpty());
    }

    @Test
    void aStaleCriminalIsPurgedOnlyAfterTheGracePeriod() {
        CrimeWorldData data = new CrimeWorldData();
        criminal(data, THIEF, CriminalJob.THIEF, 0L);

        CrimeMaintenanceSweep.sweep(data, 13L, 13L * TICKS_PER_DAY, Set.of(), Set.of(), POLICY);
        assertNotNull(data.criminalVillager(THIEF), "purged one day inside the grace period");

        CrimeMaintenanceSweep.Result after = CrimeMaintenanceSweep.sweep(data, 14L, 14L * TICKS_PER_DAY,
                Set.of(), Set.of(), POLICY);
        assertEquals(1, after.criminals());
        assertNull(data.criminalVillager(THIEF));
    }

    @Test
    void aLoadedCriminalIsNeverStale() {
        CrimeWorldData data = new CrimeWorldData();
        criminal(data, THIEF, CriminalJob.THIEF, 0L);

        CrimeMaintenanceSweep.Result result = CrimeMaintenanceSweep.sweep(data, 400L, 400L * TICKS_PER_DAY,
                Set.of(), Set.of(THIEF), POLICY);

        assertEquals(0, result.criminals());
        assertNotNull(data.criminalVillager(THIEF));
    }

    @Test
    void aContractOutlivingItsWarrantIsDropped() {
        CrimeWorldData data = new CrimeWorldData();
        UUID target = UUID.fromString("00000000-0000-0000-0000-0000000000a9");
        Warrant warrant = Warrant.open(UUID.randomUUID(), target,
                new ResourceLocation("mcacrime", "murder"), UUID.randomUUID(), 0L);
        data.putWarrant(warrant);
        BountyContractBoard.post(data, warrant, "Target", 100L, true, true, 0L);

        assertEquals(0, CrimeMaintenanceSweep.sweep(data, 1L, TICKS_PER_DAY, Set.of(), Set.of(), POLICY)
                .contracts(), "an open warrant's contract was dropped");

        data.putWarrant(warrant.closed(TICKS_PER_DAY));
        assertEquals(1, CrimeMaintenanceSweep.sweep(data, 1L, TICKS_PER_DAY, Set.of(), Set.of(), POLICY)
                .contracts());
        assertTrue(BountyContractBoard.openContracts(data).isEmpty());
    }

    @Test
    void aRestockDayBelongsToAFenceOrToNobody() {
        CrimeWorldData data = new CrimeWorldData();
        criminal(data, FENCE, CriminalJob.FENCE, 0L);
        criminal(data, THIEF, CriminalJob.THIEF, 0L);
        data.setFenceRestockDay(FENCE, 3L);
        data.setFenceRestockDay(THIEF, 3L);
        data.setFenceRestockDay(OWNER, 3L);

        CrimeMaintenanceSweep.Result result = CrimeMaintenanceSweep.sweep(data, 1L, TICKS_PER_DAY,
                Set.of(), Set.of(FENCE, THIEF), POLICY);

        assertEquals(2, result.restockDays());
        assertEquals(3L, data.fenceRestockDay(FENCE));
        assertEquals(0L, data.fenceRestockDay(THIEF));
    }
}
