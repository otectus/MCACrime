package dev.otectus.mcacrime;

import dev.otectus.mcacrime.job.CriminalJob;
import dev.otectus.mcacrime.job.HistoricalProfessionKind;
import dev.otectus.mcacrime.job.OccupationSource;
import dev.otectus.mcacrime.job.OccupationStatus;
import dev.otectus.mcacrime.state.world.CriminalVillagerRecord;
import net.minecraft.core.BlockPos;
import dev.otectus.mcacrime.state.world.WorksiteRef;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The persisted half of a criminal job, including the 0.7.2 occupational state.
 *
 * <p>Three things are load-bearing here and each has its own test. The <em>kind</em> of a remembered
 * previous profession, because "nothing was taken away" and "something was and we could not read it"
 * are different facts that were once both {@code null}-ish. The field-preserving updates, because a
 * hand-written reconstruction that missed one of eighteen components used to reset it silently. And
 * unknown keys, because a world touched by a newer build and reopened by this one must not lose that
 * build's per-villager state.
 */
class CriminalVillagerRecordTest {

    private static final UUID VILLAGER = UUID.fromString("11111111-2222-3333-4444-555555555555");
    private static final WorksiteRef STATION = WorksiteRef.of(
            new ResourceLocation("minecraft", "the_nether"), new BlockPos(7, 31, -12));

    private static CriminalVillagerRecord fence() {
        return CriminalVillagerRecord.fresh(VILLAGER, CriminalJob.FENCE, 12L, false, 987654321L,
                        OccupationSource.SETTLEMENT_SWEEP)
                .withLastMugAt(480L)
                .withLastSeenDay(19L)
                .withPreviousProfession(HistoricalProfessionKind.ID, "minecraft:cleric");
    }

    @Test
    void aFullRecordRoundTrips() {
        CriminalVillagerRecord record = fence();
        assertEquals(record, CriminalVillagerRecord.load(record.save()));
    }

    @Test
    void aFullyOccupiedThiefRoundTripsIncludingItsDimensionQualifiedStation() {
        CriminalVillagerRecord record = CriminalVillagerRecord
                .fresh(VILLAGER, CriminalJob.THIEF, 3L, false, -42L, OccupationSource.STATION_RECRUITMENT)
                .withStatus(OccupationStatus.ACTIVE_BOUND_ESTABLISHED)
                .withWorksite(STATION)
                .withEstablishedAt(51000L)
                .withVisit(50000L)
                .withEmployedTicks(24010L);

        CriminalVillagerRecord loaded = CriminalVillagerRecord.load(record.save());

        assertEquals(record, loaded);
        assertNotNull(loaded.worksite());
        assertEquals("minecraft:the_nether", loaded.worksite().dimension().toString(),
                "a position without its dimension is not a worksite (spec §10.2)");
    }

    @Test
    void aPendingReservationSurvivesARestartSoItsTicketCanBeGivenBack() {
        CriminalVillagerRecord record = CriminalVillagerRecord
                .fresh(VILLAGER, CriminalJob.NONE, 3L, false, 5L, OccupationSource.SETTLEMENT_SWEEP)
                .withReservation(STATION, 9000L);

        CriminalVillagerRecord loaded = CriminalVillagerRecord.load(record.save());

        assertEquals(STATION, loaded.reservation());
        assertEquals(9000L, loaded.reservationAt());
        assertEquals(CriminalJob.NONE, loaded.job(),
                "a candidate walking to a station is not a thief yet");
    }

    @Test
    void anAbsentPreviousProfessionStaysAbsent() {
        CriminalVillagerRecord record = CriminalVillagerRecord
                .fresh(VILLAGER, CriminalJob.THIEF, 3L, true, -42L, OccupationSource.WILD);
        CriminalVillagerRecord loaded = CriminalVillagerRecord.load(record.save());

        assertNull(loaded.previousProfessionId(), "nothing was taken away, so nothing is given back");
        assertEquals(HistoricalProfessionKind.NONE, loaded.previousProfessionKind());
        assertEquals(record, loaded);
    }

    @Test
    void anUnreadablePreviousProfessionIsNotTheSameAsNoneHavingExisted() {
        CriminalVillagerRecord record = fence()
                .withPreviousProfession(HistoricalProfessionKind.UNREADABLE, null);
        CriminalVillagerRecord loaded = CriminalVillagerRecord.load(record.save());

        assertEquals(HistoricalProfessionKind.UNREADABLE, loaded.previousProfessionKind());
        assertNull(loaded.previousProfessionId());
    }

    @Test
    void aLegacyBlankPreviousProfessionStillMeansUnreadable() {
        CompoundTag legacy = new CompoundTag();
        legacy.putUUID("villager", VILLAGER);
        legacy.putString("job", "fence");
        legacy.putString("previousProfessionId", "");

        assertEquals(HistoricalProfessionKind.UNREADABLE,
                CriminalVillagerRecord.load(legacy).previousProfessionKind(),
                "the blank string was a deliberate record of ignorance, not of absence");
    }

    @Test
    void aLegacyRecordWithNoOccupationFieldsReadsAsAnUnoccupiedOne() {
        CompoundTag legacy = new CompoundTag();
        legacy.putUUID("villager", VILLAGER);
        legacy.putString("job", "thief");
        legacy.putLong("lastMugAt", 4242L);

        CriminalVillagerRecord loaded = CriminalVillagerRecord.load(legacy);

        assertEquals(OccupationStatus.NONE, loaded.status());
        assertEquals(OccupationSource.UNKNOWN, loaded.source());
        assertEquals(4242L, loaded.lastMugAt(), "the cooldown a legacy world earned is kept");
        assertNull(loaded.worksite());
    }

    @Test
    void anUnknownJobReadsAsNone() {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("villager", UUID.randomUUID());
        tag.putString("job", "smuggler");
        assertEquals(CriminalJob.NONE, CriminalVillagerRecord.load(tag).job(),
                "a job written by a later version must not throw on an older one");
    }

    @Test
    void anUnknownStatusOrSourceReadsConservativelyRatherThanThrowing() {
        CompoundTag tag = fence().save();
        tag.putString("status", "on_secondment");
        tag.putString("source", "a_future_route");

        CriminalVillagerRecord loaded = CriminalVillagerRecord.load(tag);

        assertEquals(OccupationStatus.NONE, loaded.status());
        assertEquals(OccupationSource.UNKNOWN, loaded.source());
    }

    @Test
    void keysThisBuildDoesNotRecogniseSurviveTheRoundTrip() {
        CompoundTag tag = fence().save();
        tag.putString("someFutureField", "written by 0.8.0");
        tag.putInt("anotherOne", 7);

        CompoundTag rewritten = CriminalVillagerRecord.load(tag).save();

        assertEquals("written by 0.8.0", rewritten.getString("someFutureField"));
        assertEquals(7, rewritten.getInt("anotherOne"));
    }

    @Test
    void aKnownKeyAlwaysWinsOverAStaleUnknownCopyOfItself() {
        CriminalVillagerRecord record = fence().withStatus(OccupationStatus.SUSPENDED);
        assertEquals("suspended", record.save().getString("status"));
    }

    @Test
    void everyFieldPreservingUpdateChangesOneThingAndKeepsTheRest() {
        CriminalVillagerRecord base = fence();

        assertEquals(CriminalJob.THIEF, base.withJob(CriminalJob.THIEF).job());
        assertEquals(base.lastMugAt(), base.withJob(CriminalJob.THIEF).lastMugAt());
        assertEquals(base.previousProfessionId(), base.withStatus(OccupationStatus.PENDING)
                .previousProfessionId());
        assertEquals(base.personalitySeed(), base.withLastSeenDay(99L).personalitySeed());
        assertEquals(99L, base.withLastSeenDay(99L).lastSeenDay());
        assertEquals(7L, base.withLastMugAt(7L).lastMugAt());
        assertEquals(OccupationSource.OPERATOR, base.withSource(OccupationSource.OPERATOR).source());
        assertEquals(55L, base.withVisit(55L).lastVisitAt());
        assertEquals(24L, base.withEmployedTicks(24L).employedTicks());
        assertEquals(0L, base.withEmployedTicks(-5L).employedTicks(), "employment never goes negative");
        assertEquals(31L, base.withEstablishedAt(31L).establishedAt());
        assertEquals(12L, base.withUnboundSince(12L).unboundSince());
        assertEquals(base.assignedDay(), base.withWorksite(STATION).assignedDay());
    }

    @Test
    void bindingAStationClearsThePendingReservationAndTheUnboundClock() {
        CriminalVillagerRecord walking = fence()
                .withReservation(STATION, 100L)
                .withUnboundSince(50L);

        CriminalVillagerRecord bound = walking.withWorksite(STATION);

        assertNull(bound.reservation(), "a reservation that became a claim is no longer pending");
        assertEquals(0L, bound.reservationAt());
        assertEquals(0L, bound.unboundSince());
    }

    @Test
    void clearingAReservationClearsItsTimestampToo() {
        CriminalVillagerRecord cleared = fence().withReservation(STATION, 100L).withReservation(null, 0L);
        assertNull(cleared.reservation());
        assertEquals(0L, cleared.reservationAt());
    }

    @Test
    void retirementKeepsTheCooldownAndTheHistoryItEarned() {
        CriminalVillagerRecord thief = CriminalVillagerRecord
                .fresh(VILLAGER, CriminalJob.THIEF, 4L, false, 99L, OccupationSource.OPERATOR)
                .withLastMugAt(7777L)
                .withWorksite(STATION)
                .withPreviousProfession(HistoricalProfessionKind.ID, "minecraft:farmer");

        CriminalVillagerRecord retired = thief.retired();

        assertEquals(CriminalJob.NONE, retired.job());
        assertEquals(OccupationStatus.RETIRED, retired.status());
        assertNull(retired.worksite(), "the claim goes with the role");
        assertEquals(7777L, retired.lastMugAt(), "being fired is not a way to reset a mug cooldown");
        assertEquals(4L, retired.assignedDay());
        assertEquals(99L, retired.personalitySeed());
        assertEquals("minecraft:farmer", retired.previousProfessionId());
        assertFalse(retired.status().mayMug());
    }

    @Test
    void repeatedSaveAndLoadIsStable() {
        CriminalVillagerRecord record = fence().withWorksite(STATION).withStatus(OccupationStatus.SUSPENDED);
        CriminalVillagerRecord once = CriminalVillagerRecord.load(record.save());
        CriminalVillagerRecord twice = CriminalVillagerRecord.load(once.save());

        assertEquals(once, twice);
        assertTrue(twice.save().contains("worksite"));
    }
}
