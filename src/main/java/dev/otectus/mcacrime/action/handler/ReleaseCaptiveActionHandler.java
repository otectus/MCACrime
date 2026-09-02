package dev.otectus.mcacrime.action.handler;

import dev.otectus.mcacrime.action.*;
import dev.otectus.mcacrime.captivity.*;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;

import java.util.UUID;

/** Ends an unlawful custody the actor owns, with no ransom and no payment. */
public final class ReleaseCaptiveActionHandler implements CrimeActionHandler {

    private static final ActionDescriptor DESCRIPTOR = ActionDescriptor.of(CrimeActionIds.RELEASE_CAPTIVE,
            ActionCategory.RESTRAIN, ActionLegality.LAWFUL, ActionDuration.INSTANT, false,
            ActionRequirement.OWN_CAPTIVE);

    @Override
    public ActionDescriptor descriptor() {
        return DESCRIPTOR;
    }

    @Override
    public ActionAvailability evaluate(CrimeActor actor, LivingEntity target, ServerLevel level, long now) {
        return CustodyRegistry.isActiveKidnapperOf(level.getServer(), actor.id(), target.getUUID())
                ? ActionAvailability.available() : ActionAvailability.hidden("mcacrime.release.not_owned");
    }

    @Override
    public ActionResult start(CrimeActor actor, LivingEntity target, ServerLevel level, UUID nonce) {
        if (!evaluate(actor, target, level, level.getGameTime()).isAvailable())
            return ActionResult.rejected("mcacrime.release.not_owned");
        CustodyService.release(level.getServer(), target.getUUID(), CustodyReleaseReason.RELEASED_BY_CAPTOR);
        actor.sendMessage(Component.translatable("mcacrime.release.done"));
        return ActionResult.accepted("mcacrime.release.done");
    }

    @Override
    public void tick(ActionSession session, CrimeActor actor, LivingEntity target, ServerLevel level) {}
}
