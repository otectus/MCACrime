package dev.otectus.mcacrime.api.event;

import net.minecraft.server.level.ServerPlayer;

/**
 * Fired only when a player's Wanted status (Heat ≥ threshold, spec §1.2) actually flips — not on every
 * Heat change. Listeners (guard AI in later phases, HUD, other mods) can treat this as the edge where
 * active pursuit begins or ends.
 */
public final class WantedStatusChangedEvent extends CrimeEvent {

    private final boolean wanted;
    private final long heat;

    public WantedStatusChangedEvent(ServerPlayer player, boolean wanted, long heat) {
        super(player);
        this.wanted = wanted;
        this.heat = heat;
    }

    /** The new Wanted state. */
    public boolean isWanted() {
        return wanted;
    }

    public long getHeat() {
        return heat;
    }
}
