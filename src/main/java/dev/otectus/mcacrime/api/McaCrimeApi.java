package dev.otectus.mcacrime.api;

import dev.otectus.mcacrime.crime.Band;
import dev.otectus.mcacrime.engine.CrimeState;
import net.minecraft.server.level.ServerPlayer;

/**
 * Stable, read-only public surface for MCA: Crime (spec §16). Other mods can read a player's standing
 * without touching internals; all <em>mutation</em> stays server-internal through
 * {@link CrimeState}. To react to changes, subscribe to the events in
 * {@code dev.otectus.mcacrime.api.event} on the Forge event bus.
 */
public final class McaCrimeApi {

    private McaCrimeApi() {
    }

    public static long getKarma(ServerPlayer player) {
        return CrimeState.getKarma(player);
    }

    public static long getHeat(ServerPlayer player) {
        return CrimeState.getHeat(player);
    }

    public static Band getBand(ServerPlayer player) {
        return CrimeState.getBand(player);
    }

    public static boolean isWanted(ServerPlayer player) {
        return CrimeState.isWanted(player);
    }
}
