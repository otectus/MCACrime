package dev.otectus.mcacrime.action.handler;

import dev.otectus.mcacrime.action.ActionAvailability;
import dev.otectus.mcacrime.action.ActionCategory;
import dev.otectus.mcacrime.action.ActionDescriptor;
import dev.otectus.mcacrime.action.ActionDuration;
import dev.otectus.mcacrime.action.ActionLegality;
import dev.otectus.mcacrime.action.ActionRequirement;
import dev.otectus.mcacrime.action.ActionResult;
import dev.otectus.mcacrime.action.ActionSession;
import dev.otectus.mcacrime.action.CrimeActionHandler;
import dev.otectus.mcacrime.action.CrimeActionIds;
import dev.otectus.mcacrime.action.CrimeActor;
import dev.otectus.mcacrime.civic.CivicWorkService;
import dev.otectus.mcacrime.civic.ServiceContract;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;

import java.util.UUID;

/**
 * Taking the settlement up on community service instead of paying.
 *
 * <p>It sits in the same self menu as {@code settle_case} and {@code bail} and is deliberately not a
 * screen of its own: reference §12.1 asks for the "existing action and settlement infrastructure" to
 * carry the offer and the confirmation, and a player choosing between paying the fine, buying out the
 * sentence and working it off should be choosing between three rows of one list rather than
 * navigating to a fourth interface to find the third option.
 *
 * <p>The row is hidden rather than blocked when there is no offer outstanding. A permanently visible
 * "do community service" that is never available would read as a broken feature on the great majority
 * of servers, where the switch is off.
 */
public final class CivicServiceActionHandler implements CrimeActionHandler {

    private static final ActionDescriptor DESCRIPTOR = ActionDescriptor.self(
            CrimeActionIds.CIVIC_SERVICE, ActionCategory.RESOLVE, ActionLegality.LAWFUL,
            ActionDuration.INSTANT, false, ActionRequirement.OPEN_CASE);

    @Override
    public ActionDescriptor descriptor() {
        return DESCRIPTOR;
    }

    @Override
    public ActionAvailability evaluate(CrimeActor actor, LivingEntity target, ServerLevel level, long now) {
        ServerPlayer player = actor.asPlayer();
        if (player == null || !actor.id().equals(target.getUUID())) {
            return ActionAvailability.hidden("mcacrime.action.invalid_target");
        }
        if (!CivicWorkService.enabled()) {
            return ActionAvailability.hidden("mcacrime.civic.disabled");
        }
        ServiceContract contract = CivicWorkService.openFor(level.getServer(), player.getUUID());
        if (contract != null) {
            // Visible but blocked once accepted, unlike the no-offer case: a player who has already
            // taken a contract needs to be told it is running, not to have the row silently vanish.
            return contract.active()
                    ? ActionAvailability.blocked("mcacrime.civic.in_progress")
                    : ActionAvailability.available();
        }
        // No contract yet. The row is offered exactly where the settlement path would have priced a
        // fine, which is what makes it an alternative rather than a fourth thing on the menu. Asked as
        // a dry run: drawing the menu must never mint a durable obligation.
        return CivicWorkService.eligibility(level.getServer(), player.getUUID(), null, null).eligible()
                ? ActionAvailability.available()
                : ActionAvailability.hidden("mcacrime.civic.none");
    }

    @Override
    public ActionResult start(CrimeActor actor, LivingEntity target, ServerLevel level, UUID nonce) {
        ActionAvailability availability = evaluate(actor, target, level, level.getGameTime());
        if (!availability.isAvailable()) {
            return ActionResult.rejected(availability.reason());
        }
        ServerPlayer player = actor.asPlayer();
        // Offering and accepting in one click, and deliberately so: the player has just chosen civic
        // work from the same list that offered them the fine, and a two-step "ask, then confirm" would
        // be a confirmation of a choice they have already made. The server still decides the case, the
        // task and the terms -- the click carries none of them.
        if (CivicWorkService.openFor(level.getServer(), player.getUUID()) == null) {
            CivicWorkService.Offer offer = CivicWorkService.offer(level.getServer(), player.getUUID(),
                    true, null, null, null);
            if (!offer.made()) {
                return ActionResult.rejected("mcacrime.civic.none");
            }
        }
        return CivicWorkService.accept(level.getServer(), player.getUUID(), null)
                .map(accepted -> ActionResult.accepted("mcacrime.civic.accepted",
                        net.minecraft.network.chat.Component.translatable(accepted.task().labelKey()),
                        accepted.requiredUnits()))
                .orElseGet(() -> ActionResult.rejected("mcacrime.civic.none"));
    }

    @Override
    public void tick(ActionSession session, CrimeActor actor, LivingEntity target, ServerLevel level) {
    }
}
