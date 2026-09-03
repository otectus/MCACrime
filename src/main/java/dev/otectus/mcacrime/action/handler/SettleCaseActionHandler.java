package dev.otectus.mcacrime.action.handler;

import dev.otectus.mcacrime.action.*;
import dev.otectus.mcacrime.economy.FineService;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;

import java.util.UUID;

/**
 * Paying a fine, which settles named cases oldest-first rather than simply wiping Heat.
 *
 * <p>A self action today. Once a guard can collect on the spot (spec §13.2's "Pay assessed fine"),
 * the same handler answers a guard encounter instead, which is the reason it enters through the
 * action engine now rather than staying a command-only path.
 */
public final class SettleCaseActionHandler implements CrimeActionHandler {

    private static final ActionDescriptor DESCRIPTOR = ActionDescriptor.self(CrimeActionIds.SETTLE_CASE,
            ActionCategory.RESOLVE, ActionLegality.LAWFUL, ActionDuration.INSTANT, false,
            ActionRequirement.FUNDS, ActionRequirement.OPEN_CASE);

    @Override
    public ActionDescriptor descriptor() {
        return DESCRIPTOR;
    }

    @Override
    public ActionAvailability evaluate(CrimeActor actor, LivingEntity target, ServerLevel level, long now) {
        if (actor.asPlayer() == null) return ActionAvailability.hidden("mcacrime.action.invalid_target");
        if (!actor.id().equals(target.getUUID())) return ActionAvailability.hidden("mcacrime.action.invalid_target");
        if (!dev.otectus.mcacrime.McaCrimeConfig.COMMON.enableFines.get()) {
            return ActionAvailability.hidden("mcacrime.fine.disabled");
        }
        // FineService owns the finable/barred/nothing-owed distinction and messages each one
        // differently; duplicating that ladder here would let the two answers drift apart.
        return ActionAvailability.available();
    }

    @Override
    public ActionResult start(CrimeActor actor, LivingEntity target, ServerLevel level, UUID nonce) {
        ActionAvailability availability = evaluate(actor, target, level, level.getGameTime());
        if (!availability.isAvailable()) return ActionResult.rejected(availability.reason());
        ServerPlayer player = actor.asPlayer();
        return FineService.payFine(player) == 1
                ? ActionResult.accepted("mcacrime.fine.paid")
                : ActionResult.rejected("mcacrime.action.feedback_sent");
    }

    @Override
    public void tick(ActionSession session, CrimeActor actor, LivingEntity target, ServerLevel level) {}
}
