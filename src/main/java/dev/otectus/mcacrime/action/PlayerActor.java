package dev.otectus.mcacrime.action;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;

import java.util.UUID;

/**
 * The only {@link CrimeActor} implementation that exists today. Deliberately a thin wrapper with no
 * state of its own: an actor is created per entry into the action engine and never stored, so it can
 * never go stale against the player it wraps.
 */
public record PlayerActor(ServerPlayer player) implements CrimeActor {

    /** Wraps a player, or returns {@code null} if it is not on a server level. */
    public static PlayerActor of(ServerPlayer player) {
        return player != null && player.level() instanceof ServerLevel ? new PlayerActor(player) : null;
    }

    @Override
    public UUID id() {
        return player.getUUID();
    }

    @Override
    public LivingEntity entity() {
        return player;
    }

    @Override
    public ServerLevel level() {
        return (ServerLevel) player.level();
    }

    @Override
    public ServerPlayer asPlayer() {
        return player;
    }

    @Override
    public void sendMessage(Component message) {
        player.sendSystemMessage(message);
    }

    @Override
    public void sendActionBar(Component message) {
        player.displayClientMessage(message, true);
    }
}
