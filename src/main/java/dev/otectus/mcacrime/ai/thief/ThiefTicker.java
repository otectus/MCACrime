package dev.otectus.mcacrime.ai.thief;

import dev.otectus.mcacrime.McaCrime;
import dev.otectus.mcacrime.enforcement.ActiveIncidentRegistry;
import dev.otectus.mcacrime.mug.npc.NpcMugAbortReason;
import dev.otectus.mcacrime.mug.npc.NpcMuggingService;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.LivingEntity;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

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
@EventBusSubscriber(modid = McaCrime.MOD_ID)
public final class ThiefTicker {

    private ThiefTicker() {
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        if (server == null) {
            return;
        }
        // Occupation first: a thief whose profession an external mod changed this tick must stop
        // being driven before the behaviour service considers driving it (0.7.2 §10.4).
        dev.otectus.mcacrime.job.ThiefOccupationLifecycle.tick(server);
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
        // Spec §10.4's death row: existing loot/custody handling first, then release the station claim
        // and retire the occupation. Doing it here rather than on unload is the whole distinction --
        // an unloaded thief keeps its claim, a dead one does not.
        MinecraftServer server = net.neoforged.neoforge.server.ServerLifecycleHooks.getCurrentServer();
        if (server != null) {
            dev.otectus.mcacrime.job.ThiefOccupationLifecycle.onDeath(server, entity);
        }
    }

    /** Job removed/changed or thieves disabled: end the session before releasing its controller. */
    public static void stop(UUID entity) {
        stop(entity, NpcMugAbortReason.CANCELLED);
    }

    /**
     * The same teardown with the reason the caller actually has (0.7.2).
     *
     * <p>Added so a thief who has become law does not report "cancelled" to the victim's HUD and to
     * {@code CrimeAttemptEvent.Ended}: the reason travels through the one teardown path rather than a
     * second one being written beside it, which is what keeps HUD, reservation, active incident, pose
     * and control released exactly once.
     */
    public static void stop(UUID entity, NpcMugAbortReason reason) {
        end(entity, reason);
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
        dev.otectus.mcacrime.job.ThiefOccupationLifecycle.clearAll();
    }

    /** Ends whatever this entity was doing as a thief. A no-op for the vast majority of entities. */
    private static void end(UUID entity, NpcMugAbortReason reason) {
        NpcMuggingService.sessionForThief(entity)
                .ifPresent(session -> NpcMuggingService.abort(session.victimId(), reason));
        ThiefBehaviorService.untrack(entity);
    }
}
