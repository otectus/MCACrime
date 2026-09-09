package dev.otectus.mcacrime.action.handler;

import dev.otectus.mcacrime.action.*;
import dev.otectus.mcacrime.compat.McaCompat;
import dev.otectus.mcacrime.item.weapon.WeaponDetector;
import dev.otectus.mcacrime.memory.ApologyStatus;
import dev.otectus.mcacrime.memory.VictimMemoryService;
import dev.otectus.mcacrime.state.world.ServerMutationGate;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;

import java.util.UUID;

/** A bounded restorative option: it can repair negative hearts to zero, never farm positive hearts. */
public final class ApologizeActionHandler implements CrimeActionHandler {

    private static final ActionDescriptor DESCRIPTOR = ActionDescriptor.of(CrimeActionIds.APOLOGIZE,
            ActionCategory.RESOLVE, ActionLegality.LAWFUL, ActionDuration.INSTANT, false);

    @Override
    public ActionDescriptor descriptor() {
        return DESCRIPTOR;
    }

    @Override
    public ActionAvailability evaluate(CrimeActor actor, LivingEntity target, ServerLevel level, long now) {
        ServerPlayer player = actor.asPlayer();
        // Hearts are a player-villager relationship in MCA; an NPC actor has nothing to apologize with.
        if (player == null) return ActionAvailability.hidden("mcacrime.action.invalid_target");
        if (!McaCompat.isMcaVillager(target)) return ActionAvailability.hidden("mcacrime.action.invalid_target");
        if (McaCompat.isVillagerSleeping(target)) return ActionAvailability.blocked("mcacrime.action.target_asleep");
        if (!ServerMutationGate.allows(level.getServer()))
            return ActionAvailability.blocked("mcacrime.readonly");
        if (WeaponDetector.isArmed(player))
            return ActionAvailability.blocked("mcacrime.apologize.lower_weapon");
        if (ActionSessionManager.activeCoerciveAgainst(target.getUUID()).isPresent())
            return ActionAvailability.blocked("mcacrime.apologize.active_threat");
        ApologyStatus status = VictimMemoryService.apologyStatus(level.getServer(), target.getUUID(), actor.id(), now);
        return status == ApologyStatus.READY
                ? ActionAvailability.available()
                : ActionAvailability.blocked(status.reason());
    }

    @Override
    public ActionResult start(CrimeActor actor, LivingEntity target, ServerLevel level, UUID nonce) {
        ActionAvailability availability = evaluate(actor, target, level, level.getGameTime());
        if (!availability.isAvailable()) return ActionResult.rejected(availability.reason());
        if (!VictimMemoryService.apologize(level.getServer(), target.getUUID(), actor.id(), level.getGameTime()))
            return ActionResult.rejected("mcacrime.apologize.not_needed");
        if (McaCompat.getHearts(actor.asPlayer(), target) < 0) McaCompat.addHearts(actor.asPlayer(), target, 1);
        actor.sendMessage(Component.translatable("mcacrime.apologize.done"));
        return ActionResult.accepted("mcacrime.apologize.done");
    }

    @Override
    public void tick(ActionSession session, CrimeActor actor, LivingEntity target, ServerLevel level) {}
}
