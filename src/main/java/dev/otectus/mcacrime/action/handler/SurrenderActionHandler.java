package dev.otectus.mcacrime.action.handler;

import dev.otectus.mcacrime.action.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;

import java.util.UUID;

/** Giving yourself up to a nearby authority. A self action, like escape. */
public final class SurrenderActionHandler implements CrimeActionHandler {

    private static final ActionDescriptor DESCRIPTOR = ActionDescriptor.self(CrimeActionIds.SURRENDER,
            ActionCategory.RESOLVE, ActionLegality.LAWFUL, ActionDuration.INSTANT, false);

    @Override
    public ActionDescriptor descriptor() {
        return DESCRIPTOR;
    }

    @Override
    public ActionAvailability evaluate(CrimeActor actor, LivingEntity target, ServerLevel level, long now) {
        if (actor.asPlayer() == null) return ActionAvailability.hidden("mcacrime.action.invalid_target");
        if (!actor.id().equals(target.getUUID())) return ActionAvailability.hidden("mcacrime.action.invalid_target");
        // Whether an authority is actually in range is SurrenderService's judgement, and it explains
        // itself to the player. Re-deriving it here would duplicate the rule in two places.
        return ActionAvailability.available();
    }

    @Override
    public ActionResult start(CrimeActor actor, LivingEntity target, ServerLevel level, UUID nonce) {
        if (!evaluate(actor, target, level, level.getGameTime()).isAvailable()) {
            return ActionResult.rejected("mcacrime.action.invalid_target");
        }
        ServerPlayer player = actor.asPlayer();
        return dev.otectus.mcacrime.economy.SurrenderService.surrender(player) == 1
                ? ActionResult.accepted("mcacrime.surrender.done")
                : ActionResult.rejected("mcacrime.action.feedback_sent");
    }

    @Override
    public void tick(ActionSession session, CrimeActor actor, LivingEntity target, ServerLevel level) {}
}
