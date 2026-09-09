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
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

/** Ticks only active action sessions; no world-wide scan. */
@EventBusSubscriber(modid = McaCrime.MOD_ID)
public final class CrimeActionTicker {

    /**
     * How often a channel sends a progress update, in ticks of channel progress. Five is smooth
     * enough for a bar and is a twentieth of the packet volume of updating every tick, which matters
     * because this is per active session on a server that may have many.
     */
    private static final int PROGRESS_INTERVAL_TICKS = 5;

    private CrimeActionTicker() {}

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
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
            } else if (target.isSleeping() && session.actionId().equals(CrimeActionIds.MUG)) {
                ActionSessionManager.cancel(session, CancelReason.CONFLICT);
            } else {
                CrimeActionHandler handler = ActionHandlerRegistry.get(session.actionId());
                if (handler == null) {
                    ActionSessionManager.cancel(session, CancelReason.CONFLICT);
                } else {
                    int before = session.progress();
                    handler.tick(session, new PlayerActor(actor), target, level);
                    // Only while the session is still live: a handler that finished or cancelled has
                    // already sent its own terminal packet, and a progress update after it would
                    // resurrect the bar it just dismissed.
                    if (session.progress() != before
                            && ActionSessionManager.forActor(session.actorId()).isPresent()
                            && session.progress() % PROGRESS_INTERVAL_TICKS == 0) {
                        ActionFeedback.progress(session);
                    }
                }
            }
        }
    }
}
