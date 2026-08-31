package dev.otectus.mcacrime.action;

import dev.otectus.mcacrime.McaCrime;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.server.ServerLifecycleHooks;

/** Ticks only active action sessions; no world-wide scan. */
@Mod.EventBusSubscriber(modid = McaCrime.MOD_ID)
public final class CrimeActionTicker {
    private CrimeActionTicker() {}

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) return;
        for (ActionSession session : ActionSessionManager.active()) {
            ServerPlayer actor = server.getPlayerList().getPlayer(session.actorId());
            ServerLevel level = server.getLevel(ResourceKey.create(Registries.DIMENSION, session.dimension()));
            Entity entity = level == null ? null : level.getEntity(session.targetId());
            if (actor == null) {
                ActionSessionManager.cancel(session, CancelReason.ACTOR_GONE);
            } else if (level == null || actor.level() != level) {
                ActionSessionManager.cancel(session, CancelReason.DIMENSION_CHANGED);
            } else if (!(entity instanceof LivingEntity target) || !target.isAlive()) {
                ActionSessionManager.cancel(session, CancelReason.TARGET_GONE);
            } else {
                CrimeActionHandler handler = ActionHandlerRegistry.get(session.actionId());
                if (handler == null) ActionSessionManager.cancel(session, CancelReason.CONFLICT);
                else handler.tick(session, actor, target, level);
            }
        }
    }
}
