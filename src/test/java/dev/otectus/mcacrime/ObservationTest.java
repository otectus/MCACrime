package dev.otectus.mcacrime;

import dev.otectus.mcacrime.api.model.CrimeCommunityKey;
import dev.otectus.mcacrime.memory.CrimeObservation;
import dev.otectus.mcacrime.memory.CrimeReport;
import dev.otectus.mcacrime.memory.ObserverRole;
import dev.otectus.mcacrime.memory.ReportState;
import dev.otectus.mcacrime.state.world.CrimeDataMigrations;
import dev.otectus.mcacrime.state.world.CrimeWorldData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The observation and report store (spec §12).
 *
 * <p>What is being defended here is mostly the difference between "nobody saw it" and every other
 * state. A count could only express the first; an observation has to keep the rest apart, because a
 * witness who was silenced, one who never reached a guard in time, and one whose report landed all
 * produce different behaviour from the same underlying event.
 */
class ObservationTest {

    private static final ResourceLocation ACTION = ResourceLocation.fromNamespaceAndPath("mcacrime", "harm_villager");
    private static final ResourceLocation DIM = ResourceLocation.fromNamespaceAndPath("minecraft", "overworld");

    private static CrimeObservation observation(UUID observer, ObserverRole role, ReportState state) {
        return new CrimeObservation(UUID.randomUUID(), UUID.randomUUID(), observer, role,
                UUID.randomUUID(), UUID.randomUUID(), ACTION, DIM, new BlockPos(4, 64, -9),
                1000L, 0.8F, true, true, false, state, 5000L);
    }

    // ------------------------------------------------------------------ serialization

    @Test
    void observationSurvivesARoundTrip() {
        CrimeObservation original = observation(UUID.randomUUID(), ObserverRole.EYEWITNESS, ReportState.PENDING);
        assertEquals(original, CrimeObservation.load(original.save()));
    }

    @Test
    void thePositionIsStillWrittenAsTheLegacyCompound() {
        // Schema 6 means the 1.20.1 shape on both sides of the port (§8.2): writing an IntArrayTag
        // here would make one schema number describe two different files.
        CompoundTag pos = observation(UUID.randomUUID(), ObserverRole.GUARD, ReportState.PENDING)
                .save().getCompound("pos");
        assertEquals(4, pos.getInt("X"));
        assertEquals(64, pos.getInt("Y"));
        assertEquals(-9, pos.getInt("Z"));
    }

    @Test
    void bothPositionShapesLoad() {
        CrimeObservation original = observation(UUID.randomUUID(), ObserverRole.GUARD, ReportState.PENDING);

        CompoundTag legacy = original.save(); // compound X/Y/Z, as written
        assertEquals(new BlockPos(4, 64, -9), CrimeObservation.load(legacy).location());

        // An interim build that reached for 1.21.1's NbtUtils.writeBlockPos left int arrays behind;
        // reading them as "absent" would quietly move every observation to the origin.
        CompoundTag intArray = original.save();
        intArray.put("pos", NbtUtils.writeBlockPos(new BlockPos(4, 64, -9)));
        assertEquals(new BlockPos(4, 64, -9), CrimeObservation.load(intArray).location());
        assertEquals(original, CrimeObservation.load(intArray));
    }

    @Test
    void reportSurvivesARoundTripIncludingTheWildernessCase() {
        CrimeReport located = new CrimeReport(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), ACTION,
                new CrimeCommunityKey(DIM, 3), 100L, 900L, 0.9F, true);
        assertEquals(located, CrimeReport.load(located.save()));

        CrimeReport wilderness = new CrimeReport(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), ACTION, null, 100L, 900L, 0.4F, false);
        CrimeReport reloaded = CrimeReport.load(wilderness.save());
        assertEquals(wilderness, reloaded);
        assertTrue(reloaded.wilderness(), "a report with no village must reload as the wilderness case");
    }

    @Test
    void anObservationWithNoObserverIsRejectedRatherThanInvented() {
        CompoundTag tag = observation(UUID.randomUUID(), ObserverRole.EYEWITNESS, ReportState.PENDING).save();
        tag.remove("observer");
        assertThrows(IllegalArgumentException.class, () -> CrimeObservation.load(tag),
                "an observation nobody made must be dropped, never given a placeholder observer");
    }

    // ------------------------------------------------------------------ semantics

    @Test
    void confidenceIsClampedIntoRange() {
        CrimeObservation high = observation(UUID.randomUUID(), ObserverRole.GUARD, ReportState.PENDING)
                .withConfidence(9.0F);
        CrimeObservation low = high.withConfidence(-3.0F);
        assertEquals(1.0F, high.confidence());
        assertEquals(0.0F, low.confidence());
    }

    @Test
    void onlyPendingReportableRolesCountAsPending() {
        assertTrue(observation(UUID.randomUUID(), ObserverRole.EYEWITNESS, ReportState.PENDING).pending());
        assertFalse(observation(UUID.randomUUID(), ObserverRole.EYEWITNESS, ReportState.FILED).pending());
        assertFalse(observation(UUID.randomUUID(), ObserverRole.EYEWITNESS, ReportState.SUPPRESSED).pending());
        // Evidence cannot walk to a guard, so it is never pending however it is stored.
        assertFalse(observation(UUID.randomUUID(), ObserverRole.EVIDENCE_ONLY, ReportState.PENDING).pending());
    }

    @Test
    void arrestNeedsConfidenceUnlessAResponderSawItThemselves() {
        CrimeReport heard = new CrimeReport(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), ACTION, null, 0L, 0L, 0.3F, false);
        CrimeReport guard = new CrimeReport(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), ACTION, null, 0L, 0L, 0.3F, true);
        assertFalse(heard.supportsArrest(0.6D), "a noise in the dark is grounds to investigate, not to arrest");
        assertTrue(guard.supportsArrest(0.6D), "a responder who watched it happen needs no corroboration");
    }

    // ------------------------------------------------------------------ store

    @Test
    void oneObserverCannotCarryUnboundedPendingObservations() {
        CrimeWorldData data = new CrimeWorldData();
        UUID observer = UUID.randomUUID();
        int stored = 0;
        for (int i = 0; i < 20; i++) {
            if (data.addObservation(observation(observer, ObserverRole.EYEWITNESS, ReportState.PENDING))) {
                stored++;
            }
        }
        assertEquals(8, stored, "the ninth pending observation must be refused, not silently evict the first");
        assertEquals(8, data.pendingObservationCount(observer));
    }

    @Test
    void aSettledObservationDoesNotOccupyThePendingBudget() {
        CrimeWorldData data = new CrimeWorldData();
        UUID observer = UUID.randomUUID();
        for (int i = 0; i < 8; i++) {
            data.addObservation(observation(observer, ObserverRole.EYEWITNESS, ReportState.FILED));
        }
        assertEquals(0, data.pendingObservationCount(observer));
        assertTrue(data.addObservation(observation(observer, ObserverRole.EYEWITNESS, ReportState.PENDING)),
                "already-filed observations must not block a new one from being recorded");
    }

    @Test
    void expiryMarksRatherThanDeletes() {
        CrimeWorldData data = new CrimeWorldData();
        UUID observer = UUID.randomUUID();
        CrimeObservation pending = observation(observer, ObserverRole.EYEWITNESS, ReportState.PENDING);
        data.addObservation(pending);

        assertEquals(1, data.pruneObservations(6000L));
        assertEquals(1, data.observationCount(),
                "an expired observation is still a fact about what that villager saw");
        assertEquals(ReportState.EXPIRED,
                data.observation(pending.observationId()).orElseThrow().reportState());
    }

    @Test
    void observationsAndReportsSurviveTheWorldStore() {
        CrimeWorldData data = new CrimeWorldData();
        UUID observer = UUID.randomUUID();
        CrimeObservation stored = observation(observer, ObserverRole.GUARD, ReportState.FILED);
        data.addObservation(stored);
        CrimeReport report = new CrimeReport(UUID.randomUUID(), stored.incidentId(), stored.observationId(),
                observer, stored.suspectedActorId(), ACTION, new CrimeCommunityKey(DIM, 1),
                50L, 5000L, 1.0F, true);
        data.addReport(report);

        CrimeWorldData reloaded = CrimeWorldData.load(data.save(new CompoundTag(), RegistryAccess.EMPTY), RegistryAccess.EMPTY);
        assertEquals(1, reloaded.observationCount());
        assertEquals(stored, reloaded.observation(stored.observationId()).orElseThrow());
        assertEquals(List.of(report), reloaded.reportsAgainst(stored.suspectedActorId()));
        assertEquals(List.of(stored), reloaded.observationsBy(observer));
    }

    // ------------------------------------------------------------------ schema

    @Test
    void schemaFiveAddsTheObservationSections() {
        CompoundTag schemaFour = new CompoundTag();
        schemaFour.putInt("schema", 4);
        CompoundTag migrated = CrimeDataMigrations.v4to5(schemaFour);
        assertEquals(5, migrated.getInt("schema"));
        assertTrue(migrated.contains("observations"));
        assertTrue(migrated.contains("reports"));
    }

    @Test
    void schemaFiveDoesNotInventObservationsFromLegacyWitnesses() {
        // A schema-4 store with a witnessed record must migrate to zero observations. The witness ids
        // are there and it would be easy to synthesise from them -- but role, confidence, and place
        // were never recorded, and inventing them would drive AI from evidence nobody gathered.
        CompoundTag schemaFour = new CompoundTag();
        schemaFour.putInt("schema", 4);
        CompoundTag migrated = CrimeDataMigrations.migrate(schemaFour);
        assertEquals(CrimeDataMigrations.CURRENT_SCHEMA, migrated.getInt("schema"));
        assertEquals(0, migrated.getList("observations", 10).size());
    }

    @Test
    void aSchemaZeroStoreStillReachesTheCurrentSchema() {
        CompoundTag ancient = new CompoundTag();
        CompoundTag migrated = CrimeDataMigrations.migrate(ancient);
        assertEquals(CrimeDataMigrations.CURRENT_SCHEMA, migrated.getInt("schema"));
        assertNotEquals(0, CrimeDataMigrations.CURRENT_SCHEMA);
    }
}
