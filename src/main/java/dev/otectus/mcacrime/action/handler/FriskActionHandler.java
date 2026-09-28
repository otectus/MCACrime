package dev.otectus.mcacrime.action.handler;

import dev.otectus.mcacrime.action.ActionAvailability;
import dev.otectus.mcacrime.action.ActionCategory;
import dev.otectus.mcacrime.action.ActionDescriptor;
import dev.otectus.mcacrime.action.ActionDuration;
import dev.otectus.mcacrime.action.ActionLegality;
import dev.otectus.mcacrime.action.ActionResult;
import dev.otectus.mcacrime.action.ActionSession;
import dev.otectus.mcacrime.action.CrimeActionHandler;
import dev.otectus.mcacrime.action.CrimeActionIds;
import dev.otectus.mcacrime.action.CrimeActor;
import dev.otectus.mcacrime.frisk.FriskingService;
import dev.otectus.mcacrime.network.ActionValidation;
import dev.otectus.mcacrime.restraint.RestraintService;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;

import java.util.UUID;

/**
 * Searching a restrained subject from the crime menu (M5.2, reworked without the possessions box).
 *
 * <p>Instant: it opens a server-owned session and a read-only screen, and every take after that is
 * its own validated transfer. The legality shown here is {@link ActionLegality#CONTEXTUAL} because
 * the same row is a lawful search for the custodian or an on-duty guard and a robbery for anybody
 * else; {@code FriskingService.classify} decides which at open time, and the screen labels itself
 * with the answer.
 *
 * <p>Not coercive. The subject is already restrained, and this row never appears for anybody who is
 * not; a frozen bystander is the reaction layer's business and this action gives it nothing to
 * react to.
 */
public final class FriskActionHandler implements CrimeActionHandler {

    private static final ActionDescriptor DESCRIPTOR = ActionDescriptor.of(CrimeActionIds.FRISK,
            ActionCategory.RESTRAIN, ActionLegality.CONTEXTUAL, ActionDuration.INSTANT, false);

    @Override
    public ActionDescriptor descriptor() {
        return DESCRIPTOR;
    }

    /**
     * The pure half of {@link #evaluate}: who may be searched, from facts the caller established.
     *
     * @param self         the actor is the target
     * @param restrainable the target is something the restraint engine can hold at all
     * @param helpless     the target meets the configured restraint requirement
     * @param inReach      the target is within the frisking range
     */
    public static ActionAvailability availability(boolean self, boolean restrainable, boolean helpless,
                                                  boolean inReach) {
        if (self || !restrainable) {
            // Going through your own pockets moves nothing anywhere, and a target the engine cannot
            // hold cannot be helpless: neither is a row worth drawing.
            return ActionAvailability.hidden("");
        }
        if (!helpless) {
            return ActionAvailability.blocked("mcacrime.msg.frisk.not_restrained");
        }
        if (!inReach) {
            return ActionAvailability.blocked("mcacrime.msg.frisk.out_of_reach");
        }
        return ActionAvailability.available();
    }

    @Override
    public ActionAvailability evaluate(CrimeActor actor, LivingEntity target, ServerLevel level, long now) {
        ServerPlayer player = actor.asPlayer();
        if (player == null) {
            return ActionAvailability.hidden("");
        }
        boolean self = player.getUUID().equals(target.getUUID());
        boolean restrainable = RestraintService.restrainable(target);
        boolean helpless = restrainable && FriskingService.restraintSatisfied(
                RestraintService.state(target), FriskingService.requiresArmRestraint());
        boolean inReach = ActionValidation.inReach(player, target, FriskingService.maxRange());
        return availability(self, restrainable, helpless, inReach);
    }

    @Override
    public ActionResult start(CrimeActor actor, LivingEntity target, ServerLevel level, UUID nonce) {
        ActionAvailability available = evaluate(actor, target, level, level.getGameTime());
        if (!available.isAvailable()) {
            return ActionResult.rejected(available.reason());
        }
        if (!FriskingService.open(actor.asPlayer(), target)) {
            return ActionResult.rejected("mcacrime.msg.frisk.busy");
        }
        return ActionResult.accepted("mcacrime.action.feedback_sent");
    }

    @Override
    public void tick(ActionSession session, CrimeActor actor, LivingEntity target, ServerLevel level) {
    }
}
