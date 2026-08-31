package dev.otectus.mcacrime.action.handler;

import dev.otectus.mcacrime.action.*;
import dev.otectus.mcacrime.captivity.CaptureService;
import dev.otectus.mcacrime.captivity.RestraintType;
import dev.otectus.mcacrime.item.CrimeItems;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;

import java.util.UUID;

public final class RestrainActionHandler implements CrimeActionHandler {
    @Override public ActionAvailability evaluate(ServerPlayer actor, LivingEntity target, ServerLevel level, long now) {
        return CaptureService.evaluate(actor, target, CrimeItems.bestRestraint(actor));
    }
    @Override public ActionResult start(ServerPlayer actor, LivingEntity target, ServerLevel level, UUID nonce) {
        RestraintType restraint = CrimeItems.bestRestraint(actor);
        return CaptureService.tryBeginCapture(actor, target, restraint)
                ? ActionResult.accepted("mcacrime.capture.channeling")
                : ActionResult.rejected(evaluate(actor, target, level, level.getGameTime()).reason());
    }
    @Override public void tick(ActionSession session, ServerPlayer actor, LivingEntity target, ServerLevel level) {}
}
