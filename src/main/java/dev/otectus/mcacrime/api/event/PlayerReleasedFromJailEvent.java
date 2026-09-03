package dev.otectus.mcacrime.api.event;

import dev.otectus.mcacrime.jail.ReleaseReason;
import net.minecraft.server.level.ServerPlayer;

/**
 * Fired once when a player is released from jail (spec §16), from any release path (sentence served,
 * captivity cap, admin, pardon, invalid-jail). Idempotent at the source: a second release of an
 * already-free player fires nothing.
 */
public final class PlayerReleasedFromJailEvent extends CrimeEvent {

    private final ReleaseReason reason;

    public PlayerReleasedFromJailEvent(ServerPlayer player, ReleaseReason reason) {
        super(player);
        this.reason = reason;
    }

    public ReleaseReason getReason() {
        return reason;
    }
}
