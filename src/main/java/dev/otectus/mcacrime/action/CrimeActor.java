package dev.otectus.mcacrime.action;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;

import org.jetbrains.annotations.Nullable;
import java.util.UUID;

/**
 * Whoever is performing an action. Today every actor is a {@link net.minecraft.server.level.ServerPlayer},
 * but the action engine must stop assuming that before NPC-on-NPC crime can exist: spec §10.5 is
 * explicit that NPC crime may only be built once {@code CrimeActor} no longer names a player, because
 * the alternative is a parallel "fake" simulator that bypasses the finite purses, observation and case
 * systems the player path is bound by.
 *
 * <p>Handlers that genuinely need player internals — an inventory, a chat target — call
 * {@link #asPlayer()} and handle {@code null}. Everything expressible against this interface should be,
 * so that the eventual NPC actor inherits it for free rather than needing a second implementation of
 * the same rule.
 */
public interface CrimeActor {

    /** Stable identity, used for locks, cooldowns, memory keys and daily counters. */
    UUID id();

    /** The acting entity, for position, line of sight and reach checks. */
    LivingEntity entity();

    /** The level the actor is acting in. */
    ServerLevel level();

    /**
     * The actor as a player, or {@code null} for a non-player actor. Prefer an interface method over
     * calling this; every {@code asPlayer()} call site is a place NPC crime will have to revisit.
     */
    @Nullable
    net.minecraft.server.level.ServerPlayer asPlayer();

    /** Feedback in the chat log. A no-op for an actor that has nowhere to read it. */
    void sendMessage(Component message);

    /** Feedback above the hotbar, for progress and transient state. */
    void sendActionBar(Component message);

    /** True when this actor is a real player, i.e. {@link #asPlayer()} is non-null. */
    default boolean isPlayer() {
        return asPlayer() != null;
    }

    /** Convenience for the common "can I reach and see this target" gate. */
    default boolean canReach(LivingEntity target, double reachSqr) {
        LivingEntity self = entity();
        return self.distanceToSqr(target) <= reachSqr && self.hasLineOfSight(target);
    }
}
