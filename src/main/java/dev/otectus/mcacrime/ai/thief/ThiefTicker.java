package dev.otectus.mcacrime.ai.thief;

import dev.otectus.mcacrime.McaCrime;
import dev.otectus.mcacrime.enforcement.ActiveIncidentRegistry;
import dev.otectus.mcacrime.mug.npc.NpcMugAbortReason;
import dev.otectus.mcacrime.mug.npc.NpcMuggingService;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.entity.EntityLeaveLevelEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.server.ServerLifecycleHooks;

import java.util.UUID;

/**
 * Drives thieves and the muggings they are running.
 *
 * <p>Two calls per tick, both of which return immediately when nothing is happening: with no loaded
 * thieves and no open sessions this handler costs two {@code isEmpty()} checks. Thieves are tracked
 * on join rather than found by scanning, for exactly the reason reactions are event-created — a
 * world-wide villager sweep every tick to discover the same three criminals is the cost this design
 * exists to avoid.
 *
 * <p>Order matters: behaviour first, then the sessions it may have opened, so a mug that begins this
 * tick shows the victim a bar on the same tick it was threatened.
 */
@Mod.EventBusSubscriber(modid = McaCrime.MOD_ID)
public final class ThiefTicker {

    private ThiefTicker() {
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) {
            return;
        }
        ThiefBehaviorService.tick(server);
        NpcMuggingService.tick(server);
    }

    /** A thief that has just loaded starts being driven; everybody else is ignored cheaply. */
    @SubscribeEvent
    public static void onJoin(EntityJoinLevelEvent event) {
        if (!event.getLevel().isClientSide() && event.getEntity() instanceof LivingEntity living) {
            ThiefBehaviorService.track(living);
        }
    }

    /** Unloaded: the controller goes, the criminal job in world data stays. */
    @SubscribeEvent
    public static void onLeave(EntityLeaveLevelEvent event) {
        if (!event.getLevel().isClientSide()) {
            end(event.getEntity().getUUID(), NpcMugAbortReason.THIEF_DEAD);
        }
    }

    public static void confirmedDeath(UUID entity) {
        end(entity, NpcMugAbortReason.THIEF_DEAD);
    }

    /** Job removed/changed or thieves disabled: end the session before releasing its controller. */
    public static void stop(UUID entity) {
        end(entity, NpcMugAbortReason.CANCELLED);
    }

    /**
     * Drops every controller, session and open incident on shutdown, so a restart never inherits a
     * mugging that stopped mid-threat.
     */
    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        NpcMuggingService.clearAll();
        ThiefBehaviorService.clearAll();
        ActiveIncidentRegistry.clearAll();
    }

    /** Ends whatever this entity was doing as a thief. A no-op for the vast majority of entities. */
    private static void end(UUID entity, NpcMugAbortReason reason) {
        NpcMuggingService.sessionForThief(entity)
                .ifPresent(session -> NpcMuggingService.abort(session.victimId(), reason));
        ThiefBehaviorService.untrack(entity);
    }
}
