package dev.otectus.mcacrime.enforcement;

import dev.otectus.mcacrime.McaCrime;
import dev.otectus.mcacrime.McaCrimeConfig;
import dev.otectus.mcacrime.action.ActionSessionManager;
import dev.otectus.mcacrime.action.CancelReason;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.entity.EntityMountEvent;
import net.neoforged.neoforge.event.entity.living.LivingEvent;
import net.neoforged.neoforge.event.entity.player.AttackEntityEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.bus.api.ICancellableEvent;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * What a restrained player cannot do, and how slowly they move while doing the rest.
 *
 * <p>Every handler opens with the same restraint check, which short-circuits for essentially every event
 * the game fires — the same shape as {@code ContainmentHandler}'s not-jailed test. Restraint is not a
 * separate flag anybody sets; it is read from {@link ArrestPhase} through {@link ArrestPhases}, so
 * there is no way for a player to be cuffed by one system and free according to another.
 *
 * <p>The speed penalty is an attribute modifier rather than {@code MobEffects.MOVEMENT_SLOWDOWN}: a
 * potion effect would be visible in the inventory, emit particles, and — decisively — be curable with
 * a bucket of milk, which would make the restraint a suggestion. The cost of an attribute modifier is
 * that a leaked one is permanent, so it is removed on every exit from the restraining phases, again on
 * login for a phase that no longer restrains, and again on respawn, because attributes are not copied
 * across a death.
 */
/*
 * Spec §16 audit — every suppression handler, confirmed against the NeoForge 21.1 sources:
 *
 *   handler                    event                                          side    cancellation
 *   onAttack                   AttackEntityEvent                              server  ICancellableEvent; cancel alone stops the attack
 *   onRightClickItem           PlayerInteractEvent.RightClickItem             server  ICancellableEvent; cancel alone stops the use
 *   onRightClickBlock          PlayerInteractEvent.RightClickBlock            server  ICancellableEvent; setCanceled(true) also forces useBlock/useItem to FALSE
 *   onLeftClickBlock           PlayerInteractEvent.LeftClickBlock             server  ICancellableEvent; setCanceled(true) also forces useBlock/useItem to FALSE
 *   onEntityInteract           PlayerInteractEvent.EntityInteract             server  ICancellableEvent; cancel alone stops the interaction
 *   onEntityInteractSpecific   PlayerInteractEvent.EntityInteractSpecific     server  ICancellableEvent; cancel alone stops the interaction
 *   onBreak                    BlockEvent.BreakEvent                          server  ICancellableEvent; cancel alone leaves the block
 *   onMount                    EntityMountEvent                               both    ICancellableEvent; cancel alone prevents mounting
 *   onJump                     LivingEvent.LivingJumpEvent                    both    NOT cancellable — the impulse is undone after the fact (see onJump)
 *   onRespawn                  PlayerEvent.PlayerRespawnEvent                 server  NOT cancellable — observation only; re-derives the modifier
 *
 * Every handler narrows to ServerPlayer through restricted(), so the client copies are inert, and the
 * arrest phase is read from ArrestStates rather than from a player attachment, so nothing here depends
 * on attachment availability at the respawn/mount lifecycle points. Denials are rate-limited in
 * explain() at DENIAL_INTERVAL_TICKS.
 */
@EventBusSubscriber(modid = McaCrime.MOD_ID)
public final class RestraintHandlers {

    /** Stable id so a modifier left behind by a crash is recognised and removed rather than stacked. */
    private static final ResourceLocation SPEED_MODIFIER_ID = McaCrime.id("restrained_speed");

    /** Last tick each player was told they cannot do something, so a denial is explained, not repeated. */
    private static final Map<UUID, Long> LAST_DENIAL = new ConcurrentHashMap<>();
    private static final long DENIAL_INTERVAL_TICKS = 40L;

    private RestraintHandlers() {
    }

    // ------------------------------------------------------------------ state changes

    /** Applies the restraint: speed penalty on, anything in flight cancelled. */
    public static void onRestrained(ServerPlayer player) {
        applySpeedModifier(player);
        player.stopUsingItem();
        if (player.isPassenger()) {
            player.stopRiding();
        }
        // A channelled action survives nothing else about being arrested; letting one finish would let
        // a player mug somebody from inside a pair of cuffs.
        ActionSessionManager.clearFor(player.getUUID(), CancelReason.TARGET_GONE);
    }

    /** Removes the restraint. Safe to call for a player who was never restrained. */
    public static void onReleased(ServerPlayer player) {
        removeSpeedModifier(player);
        LAST_DENIAL.remove(player.getUUID());
    }

    /** Drops a player's denial stamp on logout, so the map cannot grow for the life of the server. */
    public static void forget(UUID player) {
        LAST_DENIAL.remove(player);
    }

    // ------------------------------------------------------------------ suppression

    @SubscribeEvent
    public static void onAttack(AttackEntityEvent event) {
        deny(event, event.getEntity());
    }

    @SubscribeEvent
    public static void onRightClickItem(PlayerInteractEvent.RightClickItem event) {
        deny(event, event.getEntity());
    }

    @SubscribeEvent
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        deny(event, event.getEntity());
    }

    @SubscribeEvent
    public static void onLeftClickBlock(PlayerInteractEvent.LeftClickBlock event) {
        deny(event, event.getEntity());
    }

    @SubscribeEvent
    public static void onEntityInteract(PlayerInteractEvent.EntityInteract event) {
        deny(event, event.getEntity());
    }

    @SubscribeEvent
    public static void onEntityInteractSpecific(PlayerInteractEvent.EntityInteractSpecific event) {
        deny(event, event.getEntity());
    }

    @SubscribeEvent
    public static void onBreak(BlockEvent.BreakEvent event) {
        deny(event, event.getPlayer());
    }

    @SubscribeEvent
    public static void onMount(EntityMountEvent event) {
        if (event.isMounting() && event.getEntityMounting() instanceof ServerPlayer player
                && restricted(player)) {
            event.setCanceled(true);
        }
    }

    /**
     * Jumping.
     *
     * <p>{@code LivingJumpEvent} is not cancellable, so the impulse is undone rather than prevented:
     * the event fires after the upward velocity has been written, and zeroing it there is the only
     * hook available without a mixin. A restrained player can still walk off a ledge; they simply
     * cannot hop a fence away from their escort.
     */
    @SubscribeEvent
    public static void onJump(LivingEvent.LivingJumpEvent event) {
        LivingEntity entity = event.getEntity();
        if (entity instanceof ServerPlayer player && restricted(player)) {
            Vec3 motion = player.getDeltaMovement();
            if (motion.y > 0.0) {
                player.setDeltaMovement(motion.x, 0.0, motion.z);
                player.hurtMarked = true;
            }
        }
    }

    /** Attributes are not copied across a respawn, so the modifier is re-derived from the phase. */
    @SubscribeEvent
    public static void onRespawn(PlayerEvent.PlayerRespawnEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        if (RestraintPolicy.effective(player).isPresent()) {
            applySpeedModifier(player);
        } else {
            removeSpeedModifier(player);
        }
    }

    // ------------------------------------------------------------------ internals

    /** True when this player is restrained and the server has restrictions switched on. */
    private static boolean restricted(Player player) {
        return player instanceof ServerPlayer server
                && McaCrimeConfig.COMMON.restrainedPlayerRestrictions.get()
                && RestraintPolicy.effective(server).isPresent();
    }

    private static void deny(ICancellableEvent event, Player player) {
        if (!restricted(player)) {
            return;
        }
        event.setCanceled(true);
        explain((ServerPlayer) player);
    }

    /**
     * Says why, at most once every couple of seconds.
     *
     * <p>A cancelled interaction with no explanation is indistinguishable from a broken one, and a
     * cancelled interaction that explains itself on every click is worse. The same lesson the HUD
     * outcome line already records.
     */
    private static void explain(ServerPlayer player) {
        long now = player.level().getGameTime();
        Long last = LAST_DENIAL.get(player.getUUID());
        if (last != null && now - last < DENIAL_INTERVAL_TICKS) {
            return;
        }
        LAST_DENIAL.put(player.getUUID(), now);
        player.sendSystemMessage(Component.translatable("mcacrime.arrest.restrained_denied"));
    }

    private static void applySpeedModifier(ServerPlayer player) {
        AttributeInstance speed = player.getAttribute(Attributes.MOVEMENT_SPEED);
        if (speed == null) {
            return;
        }
        double penalty = McaCrimeConfig.COMMON.escortSpeedPenalty.get();
        speed.removeModifier(SPEED_MODIFIER_ID);
        if (penalty <= 0.0) {
            return;
        }
        speed.addTransientModifier(new AttributeModifier(SPEED_MODIFIER_ID,
                -penalty, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
    }

    private static void removeSpeedModifier(ServerPlayer player) {
        AttributeInstance speed = player.getAttribute(Attributes.MOVEMENT_SPEED);
        if (speed != null) {
            speed.removeModifier(SPEED_MODIFIER_ID);
        }
    }
}
