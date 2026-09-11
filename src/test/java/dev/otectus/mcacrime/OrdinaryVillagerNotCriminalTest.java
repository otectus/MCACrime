package dev.otectus.mcacrime;

import dev.otectus.mcacrime.job.CriminalJob;
import dev.otectus.mcacrime.state.world.CrimeWorldData;
import dev.otectus.mcacrime.state.world.CriminalVillagerRecord;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Criminality is a persisted record, never an inference from a villager's appearance (0.7.0, plan §4.7).
 *
 * <p>The mugging complaint came with a suspicion attached — that ordinary villagers were being
 * mis-flagged as thieves — and it is worth pinning that they cannot be. A villager is a criminal if and
 * only if {@code CrimeWorldData} holds a {@link CriminalVillagerRecord} for them, which is what
 * {@code WorldCriminalJobService.isCriminal} reads. The profession is a label hung on top of that
 * record, off by default for thieves, and never the other way round.
 */
class OrdinaryVillagerNotCriminalTest {

    private static final UUID THIEF = UUID.nameUUIDFromBytes("thief".getBytes());
    private static final UUID FARMER = UUID.nameUUIDFromBytes("farmer".getBytes());

    @Test
    void onlyTheVillagerWithARecordIsACriminal() {
        CrimeWorldData data = new CrimeWorldData();
        data.putCriminalVillager(new CriminalVillagerRecord(THIEF, CriminalJob.THIEF, 0L, 0L, 0L, false,
                1L, null));
        assertNotNull(data.criminalVillager(THIEF));
        assertEquals(CriminalJob.THIEF, data.criminalVillager(THIEF).job());
        assertNull(data.criminalVillager(FARMER), "a villager with no record is not a criminal");
        assertEquals(1, data.criminalVillagers().size());
    }

    @Test
    void clearingTheRecordIsWhatEndsTheJob() {
        CrimeWorldData data = new CrimeWorldData();
        data.putCriminalVillager(new CriminalVillagerRecord(THIEF, CriminalJob.THIEF, 0L, 0L, 0L, false,
                1L, null));
        data.removeCriminalVillager(THIEF);
        assertNull(data.criminalVillager(THIEF));
        assertEquals(0, data.criminalVillagers().size());
    }

    @Test
    void theProfessionIsPresentationOnlyAndOffByDefaultForThieves() {
        // The registry object itself cannot be built without a bootstrap, so what is pinned here is the
        // fact that decides whether anybody ever sees the label: it is off, and the record is not.
        assertFalse(McaCrimeConfig.COMMON.presentThiefAsMcaProfession.getDefault(),
                "a thief wearing a label would be identifiable without any record being read");
    }
}
