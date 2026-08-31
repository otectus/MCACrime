package dev.otectus.mcacrime.action;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;

import java.util.UUID;

public interface CrimeActionHandler {
    ActionAvailability evaluate(ServerPlayer actor, LivingEntity target, ServerLevel level, long now);
    ActionResult start(ServerPlayer actor, LivingEntity target, ServerLevel level, UUID nonce);
    void tick(ActionSession session, ServerPlayer actor, LivingEntity target, ServerLevel level);
    default void cancel(ActionSession session, CancelReason reason) {}
}
