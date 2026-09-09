package dev.otectus.mcacrime;

import dev.otectus.mcacrime.incident.IncidentNotifications;
import dev.otectus.mcacrime.incident.IncidentService;
import dev.otectus.mcacrime.ledger.CrimeRecord;
import dev.otectus.mcacrime.ledger.Resolution;
import dev.otectus.mcacrime.state.world.CrimeDataMigrations;
import dev.otectus.mcacrime.state.world.CrimeWorldData;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class IncidentCommitTest {
    private static CrimeRecord record(UUID id) {
        return new CrimeRecord(id, UUID.randomUUID(), UUID.randomUUID(), ResourceLocation.fromNamespaceAndPath("mcacrime", "harm_villager"),
                OptionalInt.of(1), false, 100, 10, -10, 0, 0, Resolution.UNRESOLVED);
    }

    @Test void stateAndEvidenceExistBeforeAnyDeferredNotification() {
        var world = new CrimeWorldData();
        var record = record(UUID.randomUUID());
        var order = new ArrayList<String>();
        AtomicInteger state = new AtomicInteger();
        assertTrue(IncidentService.commitPrepared(world, record, () -> {
            assertTrue(world.containsRecord(record.id()));
            state.set(1); order.add("state");
            IncidentNotifications.run(() -> { assertEquals(2, state.get()); order.add("state event"); });
        }, () -> {
            assertEquals(1, state.get()); state.set(2); order.add("evidence");
            IncidentNotifications.run(() -> order.add("evidence event"));
        }, () -> {
            order.add("outbox");
            IncidentNotifications.run(() -> order.add("committed event"));
        }).isPresent());
        assertEquals(List.of("state", "evidence", "outbox", "state event", "evidence event", "committed event"), order);
    }

    @Test void duplicateIdDoesNotApplyAnyConsequencesOrNotifications() {
        var world = new CrimeWorldData(); var record = record(UUID.randomUUID());
        AtomicInteger calls = new AtomicInteger();
        assertTrue(IncidentService.commitPrepared(world, record, calls::incrementAndGet,
                calls::incrementAndGet, calls::incrementAndGet).isPresent());
        assertTrue(IncidentService.commitPrepared(world, record, calls::incrementAndGet,
                calls::incrementAndGet, calls::incrementAndGet).isEmpty());
        assertEquals(3, calls.get());
    }

    @Test void replayAfterSaveReloadDoesNotRepeatTheAct() {
        var world = new CrimeWorldData(); var record = record(UUID.randomUUID());
        world.addRecord(record);
        var loaded = CrimeWorldData.load(world.save(new CompoundTag(), net.minecraft.core.RegistryAccess.EMPTY), net.minecraft.core.RegistryAccess.EMPTY);
        assertTrue(IncidentService.commitPrepared(loaded, record, () -> fail("state replay"),
                () -> fail("evidence replay"), () -> fail("event replay")).isEmpty());
    }

    @Test void futureStoreRejectsBeforeCallbacks() {
        CompoundTag tag = new CompoundTag(); tag.putInt(CrimeDataMigrations.TAG_SCHEMA, CrimeDataMigrations.CURRENT_SCHEMA + 1);
        var world = CrimeWorldData.load(tag, net.minecraft.core.RegistryAccess.EMPTY);
        assertTrue(IncidentService.commitPrepared(world, record(UUID.randomUUID()), () -> fail("state"),
                () -> fail("evidence"), () -> fail("notification")).isEmpty());
        assertFalse(world.tryAddRecord(record(UUID.randomUUID())));
    }

    @Test void missingStoreRejectsBeforeCallbacks() {
        assertTrue(IncidentService.commitPrepared(null, record(UUID.randomUUID()), () -> fail("state"),
                () -> fail("evidence"), () -> fail("notification")).isEmpty());
    }

    @Test void reentrantSameIncidentIsAlreadyConsumed() {
        var world = new CrimeWorldData(); var record = record(UUID.randomUUID());
        assertTrue(IncidentService.commitPrepared(world, record, () -> {}, () -> {
            assertTrue(IncidentService.commitPrepared(world, record, () -> fail("nested state"),
                    () -> fail("nested evidence"), () -> fail("nested event")).isEmpty());
        }, () -> {}).isPresent());
    }

    @Test void notificationFailureDoesNotPreventLaterNotificationsOrFutureIncidents() {
        var world = new CrimeWorldData(); AtomicInteger calls = new AtomicInteger();
        assertTrue(IncidentService.commitPrepared(world, record(UUID.randomUUID()), () -> {}, () -> {
            IncidentNotifications.run(() -> { throw new IllegalStateException("test listener"); });
            IncidentNotifications.run(calls::incrementAndGet);
        }, () -> IncidentNotifications.run(calls::incrementAndGet)).isPresent());
        IncidentNotifications.run(calls::incrementAndGet);
        assertEquals(3, calls.get());
    }

    @Test void failedEvidenceExtensionDoesNotEraseCaseOrSuppressCommitNotification() {
        var world = new CrimeWorldData(); var record = record(UUID.randomUUID()); AtomicInteger calls = new AtomicInteger();
        assertTrue(IncidentService.commitPrepared(world, record, calls::incrementAndGet,
                () -> { throw new IllegalStateException("test preflight listener"); },
                () -> IncidentNotifications.run(calls::incrementAndGet)).isPresent());
        assertTrue(world.containsRecord(record.id())); assertEquals(2, calls.get());
    }

    @Test void nestedIncidentNotificationsWaitForOuterCommit() {
        var world = new CrimeWorldData(); var order = new ArrayList<String>();
        IncidentService.commitPrepared(world, record(UUID.randomUUID()), () -> {}, () -> {
            IncidentService.commitPrepared(world, record(UUID.randomUUID()), () -> {}, () -> {},
                    () -> IncidentNotifications.run(() -> order.add("inner event")));
            assertTrue(order.isEmpty());
        }, () -> { order.add("outer ready"); IncidentNotifications.run(() -> order.add("outer event")); });
        assertEquals(List.of("outer ready", "inner event", "outer event"), order);
    }

    @Test void provenanceSurvivesSaveWithoutSchemaChange() {
        var record = record(UUID.randomUUID()).withContext("combat_encounter", UUID.randomUUID().toString())
                .withContext("combat_basis", "continued_aggression").withContext("damage_attribution", "tame_owner");
        var world = new CrimeWorldData(); world.addRecord(record);
        CompoundTag tag = world.save(new CompoundTag(), net.minecraft.core.RegistryAccess.EMPTY);
        assertEquals(CrimeDataMigrations.CURRENT_SCHEMA, tag.getInt(CrimeDataMigrations.TAG_SCHEMA));
        assertEquals(record.context(), CrimeWorldData.load(tag, net.minecraft.core.RegistryAccess.EMPTY).recordById(record.id()).orElseThrow().context());
    }
}
