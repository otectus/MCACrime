package dev.otectus.mcacrime.client;

import dev.otectus.mcacrime.crime.Band;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Client-side cache of every known player's band, fed by the band-sync packets and read by the
 * nameplate renderer. Defaults to {@link Band#GREY} (the un-colored, non-destructive default) for any
 * player not yet synced. Client-only — never classloaded on a dedicated server.
 */
public final class ClientBandData {

    private static volatile Map<UUID, Band> bands = Map.of();

    private ClientBandData() {
    }

    public static void put(UUID player, Band band) {
        Map<UUID, Band> copy = new HashMap<>(bands);
        copy.put(player, band);
        bands = copy;
    }

    public static void putAll(Map<UUID, Band> updates) {
        Map<UUID, Band> copy = new HashMap<>(bands);
        copy.putAll(updates);
        bands = copy;
    }

    public static Band bandOf(UUID player) {
        return bands.getOrDefault(player, Band.GREY);
    }

    /** Clears the cache on disconnect so bands never leak across servers. */
    public static void clear() {
        bands = Map.of();
    }
}
