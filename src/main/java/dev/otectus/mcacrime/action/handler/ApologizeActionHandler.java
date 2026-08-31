package dev.otectus.mcacrime.action.handler;

import dev.otectus.mcacrime.action.*;
import dev.otectus.mcacrime.compat.McaCompat;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;

import java.util.UUID;

/** A bounded restorative option: it can repair negative hearts to zero, never farm positive hearts. */
public final class ApologizeActionHandler implements CrimeActionHandler {
    @Override public ActionAvailability evaluate(ServerPlayer actor, LivingEntity target, ServerLevel level, long now) {
        if (!McaCompat.isMcaVillager(target)) return ActionAvailability.hidden("mcacrime.action.invalid_target");
        return McaCompat.getHearts(actor, target) < 0 ? ActionAvailability.available()
                : ActionAvailability.blocked("mcacrime.apologize.not_needed");
    }
    @Override public ActionResult start(ServerPlayer actor, LivingEntity target, ServerLevel level, UUID nonce) {
        ActionAvailability availability = evaluate(actor, target, level, level.getGameTime());
        if (!availability.isAvailable()) return ActionResult.rejected(availability.reason());
        McaCompat.addHearts(actor, target, 1);
        actor.sendSystemMessage(Component.translatable("mcacrime.apologize.done"));
        return ActionResult.accepted("mcacrime.apologize.done");
    }
    @Override public void tick(ActionSession session, ServerPlayer actor, LivingEntity target, ServerLevel level) {}
}
