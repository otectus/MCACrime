package dev.otectus.mcacrime;

import dev.otectus.mcacrime.crime.type.CrimeIds;
import dev.otectus.mcacrime.ledger.CrimeRecord;
import dev.otectus.mcacrime.ledger.Resolution;
import dev.otectus.mcacrime.state.world.CrimeWorldData;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.OptionalInt;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Crime ledger persistence (spec §2.2): record NBT round-trip, idempotent append, and offender lookup ordering. */
class CrimeLedgerTest {

    private static CrimeRecord record(UUID id, UUID offender, UUID victim, OptionalInt village, boolean witnessed) {
        return new CrimeRecord(id, offender, victim, CrimeIds.KILL_VILLAGER, village, witnessed,
                12345L, 40L, -50L, 0L, 0L, Resolution.UNRESOLVED);
    }

    @Test
    void crimeRecordRoundTrips() {
        CrimeRecord r = record(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), OptionalInt.of(7), true);
        assertEquals(r, CrimeRecord.load(r.save()));
    }

    @Test
    void crimeRecordWithNullVictimAndNoVillageRoundTrips() {
        CrimeRecord r = record(UUID.randomUUID(), UUID.randomUUID(), null, OptionalInt.empty(), false);
        CrimeRecord loaded = CrimeRecord.load(r.save());
        assertEquals(r, loaded);
        assertTrue(loaded.villageId().isEmpty());
    }

    @Test
    void worldDataLedgerRoundTripsAndIsIdempotent() {
        CrimeWorldData data = new CrimeWorldData();
        UUID offender = UUID.randomUUID();
        CrimeRecord r1 = record(UUID.randomUUID(), offender, UUID.randomUUID(), OptionalInt.of(1), true);
        CrimeRecord r2 = record(UUID.randomUUID(), offender, UUID.randomUUID(), OptionalInt.of(2), false);
        data.addRecord(r1);
        data.addRecord(r2);
        data.addRecord(r1); // duplicate id -> ignored (replay-safe)
        assertEquals(2, data.ledgerSize());

        CrimeWorldData loaded = CrimeWorldData.load(data.save(new CompoundTag()));
        assertEquals(2, loaded.ledgerSize());

        List<CrimeRecord> forOffender = loaded.recordsForOffender(offender);
        assertEquals(2, forOffender.size());
        assertEquals(r2.id(), forOffender.get(0).id()); // newest first
        assertEquals(r1.id(), forOffender.get(1).id());
        assertEquals(0, loaded.recordsForOffender(UUID.randomUUID()).size());
    }
}
