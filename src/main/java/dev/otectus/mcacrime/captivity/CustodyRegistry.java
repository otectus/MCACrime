package dev.otectus.mcacrime.captivity;

import dev.otectus.mcacrime.state.world.CrimeWorldData;
import net.minecraft.server.MinecraftServer;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Accessor over the custody table in {@link CrimeWorldData} (spec §2.3), mirroring {@link
 * dev.otectus.mcacrime.jail.JailRegistry}. Pure lookups; all mutation goes through {@link CustodyService}.
 */
public final class CustodyRegistry {

    private CustodyRegistry() {
    }

    public static Optional<CustodyRecord> get(MinecraftServer server, UUID captive) {
        return Optional.ofNullable(CrimeWorldData.get(server).getCustody(captive));
    }

    public static boolean isCaptive(MinecraftServer server, UUID captive) {
        return CrimeWorldData.get(server).isCaptive(captive);
    }

    /** All active custody records held by {@code owner} (kidnapper or guard). */
    public static List<CustodyRecord> byOwner(MinecraftServer server, UUID owner) {
        List<CustodyRecord> out = new ArrayList<>();
        for (CustodyRecord record : CrimeWorldData.get(server).custodyRecords()) {
            if (record.getOwner().ownerUuid().filter(owner::equals).isPresent()) {
                out.add(record);
            }
        }
        return out;
    }

    /** True when {@code captive} is held <em>unlawfully</em> by {@code owner} — i.e. {@code owner} is an active kidnapper. */
    public static boolean isActiveKidnapperOf(MinecraftServer server, UUID owner, UUID captive) {
        CustodyRecord record = CrimeWorldData.get(server).getCustody(captive);
        return record != null && !record.isLawful() && record.getOwner().isKidnapper(owner);
    }

    /** True when {@code player} is currently holding any unlawful captive (drives Legal Target — spec §1.3). */
    public static boolean isActiveKidnapper(MinecraftServer server, UUID player) {
        for (CustodyRecord record : CrimeWorldData.get(server).custodyRecords()) {
            if (!record.isLawful() && record.getOwner().isKidnapper(player)) {
                return true;
            }
        }
        return false;
    }
}
