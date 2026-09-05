package dev.otectus.mcacrime.action.handler;

import dev.otectus.mcacrime.action.*;
import dev.otectus.mcacrime.captivity.CaptureService;
import dev.otectus.mcacrime.captivity.RestraintType;
import dev.otectus.mcacrime.item.CrimeItems;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;

import java.util.UUID;

/** Opens a capture channel with the best restraint the actor is carrying. */
public final class RestrainActionHandler implements CrimeActionHandler {

    private static final ActionDescriptor DESCRIPTOR = ActionDescriptor.of(CrimeActionIds.RESTRAIN,
            ActionCategory.RESTRAIN, ActionLegality.CRIMINAL, ActionDuration.SHORT, true,
            ActionRequirement.RESTRAINT, ActionRequirement.TARGET_VULNERABLE);

    @Override
    public ActionDescriptor descriptor() {
        return DESCRIPTOR;
    }

    @Override
    public boolean coercive() {
        return true;
    }

    @Override
    public ActionAvailability evaluate(CrimeActor actor, LivingEntity target, ServerLevel level, long now) {
        ServerPlayer player = actor.asPlayer();
        if (player == null) return ActionAvailability.hidden("mcacrime.capture.invalid");
        return CaptureService.evaluate(player, target, CrimeItems.bestRestraint(player));
    }

    @Override
    public ActionResult start(CrimeActor actor, LivingEntity target, ServerLevel level, UUID nonce) {
        ServerPlayer player = actor.asPlayer();
        if (player == null) return ActionResult.rejected("mcacrime.capture.invalid");
        RestraintType restraint = CrimeItems.bestRestraint(player);
        return CaptureService.tryBeginCapture(player, target, restraint)
                ? ActionResult.accepted("mcacrime.capture.channeling")
                : ActionResult.rejected(evaluate(actor, target, level, level.getGameTime()).reason());
    }

    @Override
    public void tick(ActionSession session, CrimeActor actor, LivingEntity target, ServerLevel level) {}
}
