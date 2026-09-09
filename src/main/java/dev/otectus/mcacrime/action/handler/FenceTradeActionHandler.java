package dev.otectus.mcacrime.action.handler;

import dev.otectus.mcacrime.McaCrimeConfig;
import dev.otectus.mcacrime.action.*;
import dev.otectus.mcacrime.compat.McaCompat;
import dev.otectus.mcacrime.economy.fence.FenceTradeService;
import dev.otectus.mcacrime.job.CriminalJob;
import dev.otectus.mcacrime.job.WorldCriminalJobService;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;

import java.util.UUID;

/**
 * Trading with a fence — the one row on the Crime menu that is not a crime.
 *
 * <p>Non-coercive, which is exactly why it exists: the menu's weapon gate lets an unarmed player
 * reach a fence and serves them the non-coercive rows only, and without this handler that would be an
 * empty screen. Hidden entirely against anybody who is not a fence, because a villager's criminal job
 * is not something a player learns by opening a menu on every villager in the village.
 */
public final class FenceTradeActionHandler implements CrimeActionHandler {

    private static final ActionDescriptor DESCRIPTOR = ActionDescriptor.of(CrimeActionIds.FENCE_TRADE,
            ActionCategory.SPECIAL, ActionLegality.LAWFUL, ActionDuration.INSTANT, false);

    @Override
    public ActionDescriptor descriptor() {
        return DESCRIPTOR;
    }

    @Override
    public ActionAvailability evaluate(CrimeActor actor, LivingEntity target, ServerLevel level, long now) {
        ServerPlayer player = actor.asPlayer();
        if (player == null) return ActionAvailability.hidden("mcacrime.action.invalid_target");
        if (!McaCrimeConfig.COMMON.enableFences.get()) {
            return ActionAvailability.hidden("mcacrime.action.invalid_target");
        }
        if (!McaCompat.isMcaVillager(target)) return ActionAvailability.hidden("mcacrime.action.invalid_target");
        MinecraftServer server = level.getServer();
        if (server == null) return ActionAvailability.hidden("mcacrime.action.invalid_target");
        if (WorldCriminalJobService.of(server).get(target.getUUID()) != CriminalJob.FENCE) {
            return ActionAvailability.hidden("mcacrime.action.invalid_target");
        }
        if (target.isSleeping()) return ActionAvailability.blocked("mcacrime.action.target_asleep");
        return ActionAvailability.available();
    }

    @Override
    public ActionResult start(CrimeActor actor, LivingEntity target, ServerLevel level, UUID nonce) {
        ActionAvailability availability = evaluate(actor, target, level, level.getGameTime());
        if (!availability.isAvailable()) return ActionResult.rejected(availability.reason());
        return FenceTradeService.open(actor.asPlayer(), target)
                ? ActionResult.accepted("mcacrime.fence.opened")
                : ActionResult.rejected("mcacrime.fence.unavailable");
    }

    @Override
    public void tick(ActionSession session, CrimeActor actor, LivingEntity target, ServerLevel level) {}
}
