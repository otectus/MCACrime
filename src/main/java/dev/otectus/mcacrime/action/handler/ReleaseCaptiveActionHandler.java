package dev.otectus.mcacrime.action.handler;

import dev.otectus.mcacrime.action.*;
import dev.otectus.mcacrime.captivity.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;

import java.util.UUID;

public final class ReleaseCaptiveActionHandler implements CrimeActionHandler {
    @Override public ActionAvailability evaluate(ServerPlayer actor, LivingEntity target, ServerLevel level, long now) {
        return CustodyRegistry.isActiveKidnapperOf(level.getServer(), actor.getUUID(), target.getUUID())
                ? ActionAvailability.available() : ActionAvailability.hidden("mcacrime.release.not_owned");
    }
    @Override public ActionResult start(ServerPlayer actor, LivingEntity target, ServerLevel level, UUID nonce) {
        if (!evaluate(actor, target, level, level.getGameTime()).isAvailable())
            return ActionResult.rejected("mcacrime.release.not_owned");
        CustodyService.release(level.getServer(), target.getUUID(), CustodyReleaseReason.RELEASED_BY_CAPTOR);
        actor.sendSystemMessage(net.minecraft.network.chat.Component.translatable("mcacrime.release.done"));
        return ActionResult.accepted("mcacrime.release.done");
    }
    @Override public void tick(ActionSession session, ServerPlayer actor, LivingEntity target, ServerLevel level) {}
}
