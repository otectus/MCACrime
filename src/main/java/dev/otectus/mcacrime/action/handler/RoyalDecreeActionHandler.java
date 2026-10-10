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
import dev.otectus.mcacrime.decree.DecreeMenuService;
import dev.otectus.mcacrime.network.CrimeNetwork;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;

import java.util.UUID;

/**
 * A village ruler hands a royal decree to a guard or court officer of their own capital (0.7.8).
 *
 * <p>Peaceful and instant: it opens the server-authored decree screen and changes nothing by itself.
 * Hidden from everybody who is not the player sovereign of the village they and the villager are both
 * in, so it never clutters an ordinary player's menu and never hints at a power they lack.
 */
public final class RoyalDecreeActionHandler implements CrimeActionHandler {

    private static final ActionDescriptor DESCRIPTOR = ActionDescriptor.of(CrimeActionIds.ROYAL_DECREE,
            ActionCategory.SPECIAL, ActionLegality.LAWFUL, ActionDuration.INSTANT, false,
            ActionRequirement.AUTHORITY);

    @Override
    public ActionDescriptor descriptor() {
        return DESCRIPTOR;
    }

    @Override
    public ActionAvailability evaluate(CrimeActor actor, LivingEntity target, ServerLevel level, long now) {
        ServerPlayer player = actor.asPlayer();
        if (player == null || DecreeMenuService.host(player, target).isEmpty()) {
            return ActionAvailability.hidden("");
        }
        return ActionAvailability.available();
    }

    @Override
    public ActionResult start(CrimeActor actor, LivingEntity target, ServerLevel level, UUID nonce) {
        ActionAvailability available = evaluate(actor, target, level, level.getGameTime());
        if (!available.isAvailable()) {
            return ActionResult.rejected("mcacrime.decree.refused.not_sovereign");
        }
        DecreeMenuService.Menu menu = DecreeMenuService.open(actor.asPlayer(), target);
        if (menu == null) {
            return ActionResult.rejected("mcacrime.decree.refused.not_sovereign");
        }
        CrimeNetwork.sendDecreeMenu(actor.asPlayer(), menu);
        return ActionResult.accepted("mcacrime.action.feedback_sent");
    }

    @Override
    public void tick(ActionSession session, CrimeActor actor, LivingEntity target, ServerLevel level) {
    }
}
