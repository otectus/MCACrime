package dev.otectus.mcacrime.action.handler;

import dev.otectus.mcacrime.action.*;
import dev.otectus.mcacrime.captivity.CustodyRegistry;
import dev.otectus.mcacrime.ransom.RansomService;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;

import java.util.UUID;

/** Contextual ransom action shown only for the exact captive unlawfully owned by the actor. */
public final class RansomActionHandler implements CrimeActionHandler {

    private static final ActionDescriptor DESCRIPTOR = ActionDescriptor.of(CrimeActionIds.RANSOM,
            ActionCategory.COERCE, ActionLegality.CRIMINAL, ActionDuration.INSTANT, true,
            ActionRequirement.OWN_CAPTIVE);

    @Override
    public ActionDescriptor descriptor() {
        return DESCRIPTOR;
    }

    @Override
    public ActionAvailability evaluate(CrimeActor actor, LivingEntity target, ServerLevel level, long now) {
        return CustodyRegistry.isActiveKidnapperOf(level.getServer(), actor.id(), target.getUUID())
                ? ActionAvailability.available() : ActionAvailability.hidden("mcacrime.ransom.notholding");
    }

    @Override
    public ActionResult start(CrimeActor actor, LivingEntity target, ServerLevel level, UUID nonce) {
        if (!evaluate(actor, target, level, level.getGameTime()).isAvailable()) {
            return ActionResult.rejected("mcacrime.ransom.notholding");
        }
        ServerPlayer player = actor.asPlayer();
        if (player == null) return ActionResult.rejected("mcacrime.ransom.notholding");
        RansomService.Outcome result = RansomService.demandOutcome(player, target.getUUID());
        // RansomService reports its own refusal reason; suppress the generic follow-up in that case.
        return result.status() == 1
                ? ActionResult.accepted("mcacrime.ransom.demanded", result.amount())
                : ActionResult.rejected("mcacrime.action.feedback_sent");
    }

    @Override
    public void tick(ActionSession session, CrimeActor actor, LivingEntity target, ServerLevel level) {}
}
