package dev.otectus.mcacrime.ledger;

import dev.otectus.mcacrime.state.world.CrimeWorldData;
import net.minecraft.server.MinecraftServer;

import java.util.List;
import java.util.UUID;

/**
 * Thin server-side accessor over the {@link CrimeWorldData} crime ledger (spec §2.2). Centralizes the
 * write path so idempotency (skip a duplicate record id) lives in one place.
 */
public final class CrimeLedger {

    private CrimeLedger() {
    }

    public static void record(MinecraftServer server, CrimeRecord record) {
        CrimeWorldData.get(server).addRecord(record);
    }

    public static List<CrimeRecord> forOffender(MinecraftServer server, UUID offender) {
        return CrimeWorldData.get(server).recordsForOffender(offender);
    }
}
