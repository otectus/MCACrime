package dev.otectus.mcacrime.action.handler;

import dev.otectus.mcacrime.action.*;
import dev.otectus.mcacrime.captivity.CustodyRegistry;
import dev.otectus.mcacrime.captivity.CustodyService;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;

import java.util.UUID;

/**
 * Working against a restraint, routed through the action engine rather than straight off a command.
 *
 * <p>This is a <em>self</em> action: the actor and the target are the same entity. There is no roll
 * left to exploit as of 0.7.5 — the probability this used to reroll is gone, and one request is one
 * bounded struggle input against one worn restraint, rate-limited on the server exactly like the
 * key-press path ({@code restraint/EscapeService}). {@link CustodyService#attemptEscape} is still the
 * single entry point, so every door reaches the same rules.
 */
public final class EscapeActionHandler implements CrimeActionHandler {

    private static final ActionDescriptor DESCRIPTOR = ActionDescriptor.self(CrimeActionIds.ESCAPE,
            ActionCategory.RESTRAIN, ActionLegality.CONTEXTUAL, ActionDuration.LONG, false);

    @Override
    public ActionDescriptor descriptor() {
        return DESCRIPTOR;
    }

    @Override
    public ActionAvailability evaluate(CrimeActor actor, LivingEntity target, ServerLevel level, long now) {
        if (actor.asPlayer() == null) return ActionAvailability.hidden("mcacrime.action.invalid_target");
        // Escape is only ever performed on yourself; a menu opened against somebody else must not show it.
        if (!actor.id().equals(target.getUUID())) return ActionAvailability.hidden("mcacrime.action.invalid_target");
        // Physically worn gear, not a custody row: a self-applied hood is not a captivity and is
        // still something to work out of, and a captive with nothing on them has nothing to struggle
        // against.
        ServerPlayer player = actor.asPlayer();
        dev.otectus.mcacrime.restraint.PhysicalRestraintState state =
                dev.otectus.mcacrime.restraint.RestraintService.state(player);
        boolean worn = state != null && state.restrained();
        return worn || CustodyRegistry.isCaptive(level.getServer(), actor.id())
                ? ActionAvailability.available()
                : ActionAvailability.hidden("mcacrime.captive.escape.not_held");
    }

    @Override
    public ActionResult start(CrimeActor actor, LivingEntity target, ServerLevel level, UUID nonce) {
        if (!evaluate(actor, target, level, level.getGameTime()).isAvailable()) {
            return ActionResult.rejected("mcacrime.captive.escape.not_held");
        }
        // CustodyService reports its own outcome — progress, nothing worn, locked or too soon — so the
        // caller must not send a second competing message on top of it.
        return CustodyService.attemptEscape(actor.asPlayer())
                ? ActionResult.accepted("mcacrime.captive.escape.started")
                : ActionResult.rejected("mcacrime.action.feedback_sent");
    }

    @Override
    public void tick(ActionSession session, CrimeActor actor, LivingEntity target, ServerLevel level) {}
}
