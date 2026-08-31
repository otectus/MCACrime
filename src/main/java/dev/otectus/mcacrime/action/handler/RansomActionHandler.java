package dev.otectus.mcacrime.action.handler;

import dev.otectus.mcacrime.action.ActionAvailability;
import dev.otectus.mcacrime.action.ActionResult;
import dev.otectus.mcacrime.action.ActionSession;
import dev.otectus.mcacrime.action.CrimeActionHandler;
import dev.otectus.mcacrime.captivity.CustodyRegistry;
import dev.otectus.mcacrime.ransom.RansomService;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;

import java.util.UUID;

/** Contextual ransom action shown only for the exact captive unlawfully owned by the actor. */
public final class RansomActionHandler implements CrimeActionHandler {
    @Override
    public ActionAvailability evaluate(ServerPlayer actor, LivingEntity target, ServerLevel level, long now) {
        return CustodyRegistry.isActiveKidnapperOf(level.getServer(), actor.getUUID(), target.getUUID())
                ? ActionAvailability.available() : ActionAvailability.hidden("mcacrime.ransom.notholding");
    }

    @Override
    public ActionResult start(ServerPlayer actor, LivingEntity target, ServerLevel level, UUID nonce) {
        if (!evaluate(actor, target, level, level.getGameTime()).isAvailable()) {
            return ActionResult.rejected("mcacrime.ransom.notholding");
        }
        int result = RansomService.demandFor(actor, target.getUUID());
        return result == 1 ? ActionResult.accepted("mcacrime.ransom.demanded")
                : ActionResult.rejected("mcacrime.action.feedback_sent");
    }

    @Override
    public void tick(ActionSession session, ServerPlayer actor, LivingEntity target, ServerLevel level) {}
}
