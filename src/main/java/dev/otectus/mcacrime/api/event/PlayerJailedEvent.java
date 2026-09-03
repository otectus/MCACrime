package dev.otectus.mcacrime.api.event;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;

import javax.annotation.Nullable;

/**
 * Fired once when a player is jailed (spec §16), on the not-already-jailed transition (re-jailing only
 * updates the sentence and fires nothing). Carries the sentence length and the jail anchor (null when
 * jailed without a resolvable anchor — e.g. fallback pending).
 */
public final class PlayerJailedEvent extends CrimeEvent {

    private final long ticks;
    @Nullable
    private final BlockPos anchor;

    public PlayerJailedEvent(ServerPlayer player, long ticks, @Nullable BlockPos anchor) {
        super(player);
        this.ticks = ticks;
        this.anchor = anchor;
    }

    public long getTicks() {
        return ticks;
    }

    @Nullable
    public BlockPos getAnchor() {
        return anchor;
    }
}
