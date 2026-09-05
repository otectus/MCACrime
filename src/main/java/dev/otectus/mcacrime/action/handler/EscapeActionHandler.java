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
 * <p>This is a <em>self</em> action: the actor and the target are the same entity. The engine still
 * gives it a session, a nonce and a replay result, which matters more here than anywhere else — the
 * 0.3.0 exploit was that repeated {@code /crime escape} calls rerolled the escape probability. The
 * single deterministic roll lives in {@link CustodyService#attemptEscape}; this handler only makes
 * sure every entry point reaches it the same way.
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
        return CustodyRegistry.isCaptive(level.getServer(), actor.id())
                ? ActionAvailability.available()
                : ActionAvailability.hidden("mcacrime.captive.escape.not_held");
    }

    @Override
    public ActionResult start(CrimeActor actor, LivingEntity target, ServerLevel level, UUID nonce) {
        if (!evaluate(actor, target, level, level.getGameTime()).isAvailable()) {
            return ActionResult.rejected("mcacrime.captive.escape.not_held");
        }
        ServerPlayer player = actor.asPlayer();
        // CustodyService reports its own outcome — started, already working, cooldown, or locked — so
        // the caller must not send a second competing message on top of it.
        return CustodyService.attemptEscape(player)
                ? ActionResult.accepted("mcacrime.captive.escape.started",
                        CustodyService.escapeWorkTicks(player))
                : ActionResult.rejected("mcacrime.action.feedback_sent");
    }

    @Override
    public void tick(ActionSession session, CrimeActor actor, LivingEntity target, ServerLevel level) {}
}
