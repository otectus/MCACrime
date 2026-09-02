package dev.otectus.mcacrime.action.handler;

import dev.otectus.mcacrime.action.*;
import dev.otectus.mcacrime.ransom.RansomService;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;

import java.util.UUID;

/** Paying a ransom demanded for a held relative. A self action; the payer is resolved server-side. */
public final class PayRansomActionHandler implements CrimeActionHandler {

    private static final ActionDescriptor DESCRIPTOR = ActionDescriptor.self(CrimeActionIds.PAY_RANSOM,
            ActionCategory.RESOLVE, ActionLegality.LAWFUL, ActionDuration.INSTANT, false,
            ActionRequirement.FUNDS);

    @Override
    public ActionDescriptor descriptor() {
        return DESCRIPTOR;
    }

    @Override
    public ActionAvailability evaluate(CrimeActor actor, LivingEntity target, ServerLevel level, long now) {
        if (actor.asPlayer() == null) return ActionAvailability.hidden("mcacrime.action.invalid_target");
        if (!actor.id().equals(target.getUUID())) return ActionAvailability.hidden("mcacrime.action.invalid_target");
        // Whether this player is the resolved payer for an open demand is RansomService's answer, and
        // it is deliberately not exposed as a menu availability: a demand naming somebody else is not
        // this player's business to see.
        return ActionAvailability.available();
    }

    @Override
    public ActionResult start(CrimeActor actor, LivingEntity target, ServerLevel level, UUID nonce) {
        if (!evaluate(actor, target, level, level.getGameTime()).isAvailable()) {
            return ActionResult.rejected("mcacrime.action.invalid_target");
        }
        ServerPlayer player = actor.asPlayer();
        return RansomService.pay(player) == 1
                ? ActionResult.accepted("mcacrime.ransom.paid")
                : ActionResult.rejected("mcacrime.action.feedback_sent");
    }

    @Override
    public void tick(ActionSession session, CrimeActor actor, LivingEntity target, ServerLevel level) {}
}
