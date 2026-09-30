package dev.otectus.mcacrime.enforcement;

import dev.otectus.mcacrime.McaCrime;
import dev.otectus.mcacrime.McaCrimeConfig;
import dev.otectus.mcacrime.action.ActionSessionManager;
import dev.otectus.mcacrime.action.CancelReason;
import dev.otectus.mcacrime.restraint.PhysicalRestraintState;
import dev.otectus.mcacrime.restraint.RestraintAction;
import dev.otectus.mcacrime.restraint.RestraintAttributes;
import dev.otectus.mcacrime.restraint.RestraintService;
import dev.otectus.mcacrime.restraint.RestrictionPolicy;
import dev.otectus.mcacrime.restraint.RestrictionResolver;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.neoforged.neoforge.event.entity.EntityMountEvent;
import net.neoforged.neoforge.event.entity.item.ItemTossEvent;
import net.neoforged.neoforge.event.entity.player.AttackEntityEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.bus.api.ICancellableEvent;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;

import org.jetbrains.annotations.Nullable;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * What a restrained player cannot do, decided per action type (0.7.5 M2.8).
 *
 * <p>The change from the previous version is the whole point of the §7.1 matrix: this class used to
 * ask "is this player restrained" and cancel <em>everything</em> if the answer was yes. It now asks
 * the composed {@link RestrictionPolicy} about the specific {@link RestraintAction} the event
 * represents. Leg shackles no longer stop somebody opening a door; arm cuffs no longer stop somebody
 * walking. Two restraints imposing the same restriction do not compound, because the policy composes
 * idempotently rather than being counted.
 *
 * <p>Enforcement is server-first. Every handler here runs on the server, on an event the server sees
 * happen; the client-side input suppression in {@code client/RestraintInputHandler} exists only so a
 * denied movement feels immediate and is never what actually enforces anything.
 *
 * <p>What is deliberately <b>not</b> here: a blanket command cancel. The source cancels every command
 * a restrained non-operator types ({@code event/ModServerEvents.onCommand}), which takes away
 * {@code /help}, {@code /msg} and every server's own report command from the one player most likely
 * to need them. Chat and the status screens are {@code ProtectedAction}s and no policy may cancel
 * them.
 */
/*
 * Spec §16 audit — every suppression handler, confirmed against the NeoForge 21.1 sources:
 *
 *   handler                    event                                          side    cancellation
 *   onAttack                   AttackEntityEvent                              server  ICancellableEvent; cancel alone stops the attack
 *   onRightClickItem           PlayerInteractEvent.RightClickItem             server  ICancellableEvent; cancel alone stops the use
 *   onRightClickBlock          PlayerInteractEvent.RightClickBlock            server  ICancellableEvent; setCanceled(true) also forces useBlock/useItem to FALSE
 *   onLeftClickBlock           PlayerInteractEvent.LeftClickBlock             server  ICancellableEvent; setCanceled(true) also forces useBlock/useItem to FALSE
 *   onEntityInteract           PlayerInteractEvent.EntityInteract             server  ICancellableEvent; LOW priority, after the restraint router
 *   onEntityInteractSpecific   PlayerInteractEvent.EntityInteractSpecific     server  ICancellableEvent; LOW priority, after the restraint router
 *   onBreak                    BlockEvent.BreakEvent                          server  ICancellableEvent; cancel alone leaves the block
 *   onItemToss                 ItemTossEvent                                  server  ICancellableEvent; cancel alone DESTROYS the stack, so it is put back first
 *   onMount                    EntityMountEvent                               both    ICancellableEvent; mounting and dismounting are two permissions
 *   onRespawn                  PlayerEvent.PlayerRespawnEvent                 server  NOT cancellable — observation only; re-derives the attribute penalties
 *   onIncomingDamage           LivingIncomingDamageEvent                      server  ICancellableEvent; the M6.2 combat backstop, raised whatever swung
 *   onUseItemStart             LivingEntityUseItemEvent.Start                 server  ICancellableEvent; the M6.2 channelled-use (spell) backstop
 *
 * Jumping is no longer handled here: LivingEvent.LivingJumpEvent is not cancellable, so 0.7.5 denies
 * it in mixin/RestraintJumpMixin at LivingEntity#jumpFromGround instead.
 *
 * Every handler narrows to ServerPlayer, so the client copies are inert. Denials are rate-limited in
 * explain() at DENIAL_INTERVAL_TICKS.
 */
@EventBusSubscriber(modid = McaCrime.MOD_ID)
public final class RestraintHandlers {

    /** Last tick each player was told they cannot do something, so a denial is explained, not repeated. */
    private static final Map<UUID, Long> LAST_DENIAL = new ConcurrentHashMap<>();
    private static final long DENIAL_INTERVAL_TICKS = 40L;

    private RestraintHandlers() {
    }

    // ------------------------------------------------------------------ state changes

    /** Applies the physical consequences: attribute penalties on, anything in flight cancelled. */
    public static void onRestrained(ServerPlayer player) {
        refresh(player);
        player.stopUsingItem();
        if (player.isPassenger() && !policy(player).dismount()) {
            player.stopRiding();
        }
        // A channelled action survives nothing else about being arrested; letting one finish would let
        // a player mug somebody from inside a pair of cuffs.
        ActionSessionManager.clearFor(player.getUUID(), CancelReason.TARGET_GONE);
    }

    /** Removes the physical consequences. Safe to call for a player who was never restrained. */
    public static void onReleased(ServerPlayer player) {
        RestraintAttributes.clear(player);
        LAST_DENIAL.remove(player.getUUID());
    }

    /**
     * Recomputes the attribute penalties from whatever is on the player right now.
     *
     * <p>One entry point rather than an apply and a remove, because "removing one restraint must not
     * restore what another still forbids" is only true if the answer is re-derived.
     */
    public static void refresh(@Nullable ServerPlayer player) {
        if (player != null) {
            RestraintAttributes.apply(player, policy(player));
        }
    }

    /** Drops a player's denial stamp on logout, so the map cannot grow for the life of the server. */
    public static void forget(UUID player) {
        LAST_DENIAL.remove(player);
    }

    // ------------------------------------------------------------------ suppression

    @SubscribeEvent
    public static void onAttack(AttackEntityEvent event) {
        deny(event, event.getEntity(), RestraintAction.ATTACK);
    }

    @SubscribeEvent
    public static void onRightClickItem(PlayerInteractEvent.RightClickItem event) {
        deny(event, event.getEntity(), RestraintAction.USE_ITEM);
    }

    /**
     * The combat backstop (0.7.5 M6.2, specification §15.1).
     *
     * <p>{@code AttackEntityEvent} is raised by the vanilla attack path. A combat mod that swings
     * through its own code - Better Combat's sweeping attacks, Epic Fight's battle-mode skills - may
     * never raise it, and the restriction would hold for a bare fist and not for the thing the mod
     * exists to add. Every one of them still deals damage, so the damage itself is where the
     * restriction is finally enforced: an attack by a subject whose arms are restrained is cancelled
     * here whatever raised it.
     *
     * <p>This is what "covered by server-side enforcement" means in {@code compat/OptionalMods}, and
     * it is the honest reason those rows need no adapter. Nothing mod-specific is named, no client
     * setting is touched, and the rule is identical whether any of them is installed.
     *
     * <p><b>1.21.1 difference from the Forge baseline.</b> The baseline hooks {@code LivingAttackEvent};
     * NeoForge 21.1 replaced it with {@link net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent},
     * which is the same moment - raised on the victim, before armour, cancellable - and carries the
     * same {@code DamageSource}.
     */
    @SubscribeEvent
    public static void onIncomingDamage(
            net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent event) {
        if (!restrictionsEnabled()) {
            return;
        }
        if (!(event.getSource().getEntity() instanceof ServerPlayer attacker)) {
            return;
        }
        if (!policy(attacker).permits(RestraintAction.ATTACK)) {
            event.setCanceled(true);
            explain(attacker);
        }
    }

    /**
     * The magic backstop, and the same argument.
     *
     * <p>A spell cast through a held item raises {@code RightClickItem} and is already denied; one
     * cast through a channelled use is a {@code LivingEntityUseItemEvent.Start}, which is a different
     * event and was not. "Restricted hand actions cannot bypass through spell input" is the
     * specification's wording, and this is the line that makes it true for every spell mod at once.
     */
    @SubscribeEvent
    public static void onUseItemStart(
            net.neoforged.neoforge.event.entity.living.LivingEntityUseItemEvent.Start event) {
        if (!restrictionsEnabled() || !(event.getEntity() instanceof ServerPlayer user)) {
            return;
        }
        if (!policy(user).permits(RestraintAction.USE_ITEM)) {
            event.setCanceled(true);
            explain(user);
        }
    }

    @SubscribeEvent
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        deny(event, event.getEntity(), RestraintAction.INTERACT_BLOCK);
    }

    @SubscribeEvent
    public static void onLeftClickBlock(PlayerInteractEvent.LeftClickBlock event) {
        deny(event, event.getEntity(), RestraintAction.MINE_BLOCKS);
    }

    /**
     * Interacting with an entity.
     *
     * <p>{@link EventPriority#LOW} so {@code restraint/RestraintInteractHandler} has already had its
     * say: a restrained subject reaching for the person holding their key must not have that
     * interaction cancelled before the removal path sees it. Self-escape is a protected action.
     */
    @SubscribeEvent(priority = EventPriority.LOW)
    public static void onEntityInteract(PlayerInteractEvent.EntityInteract event) {
        deny(event, event.getEntity(), RestraintAction.INTERACT_ENTITY);
    }

    @SubscribeEvent(priority = EventPriority.LOW)
    public static void onEntityInteractSpecific(PlayerInteractEvent.EntityInteractSpecific event) {
        deny(event, event.getEntity(), RestraintAction.INTERACT_ENTITY);
    }

    @SubscribeEvent
    public static void onBreak(BlockEvent.BreakEvent event) {
        deny(event, event.getPlayer(), RestraintAction.MINE_BLOCKS);
    }

    /**
     * Dropping an item.
     *
     * <p>Cancelling this event alone would <em>destroy</em> the stack: NeoForge's own documentation says
     * cancelling stops the item entering the world but does not stop it having already left the
     * inventory. So the stack is put back, and only then is the event cancelled.
     */
    @SubscribeEvent
    public static void onItemToss(ItemTossEvent event) {
        if (!(event.getPlayer() instanceof ServerPlayer player) || !restrictionsEnabled()) {
            return;
        }
        if (policy(player).permits(RestraintAction.DROP_ITEM)) {
            return;
        }
        event.setCanceled(true);
        player.getInventory().placeItemBackInInventory(event.getEntity().getItem());
        explain(player);
    }

    /** Mounting and dismounting are two different permissions, and the event carries which. */
    @SubscribeEvent
    public static void onMount(EntityMountEvent event) {
        if (!(event.getEntityMounting() instanceof ServerPlayer player) || !restrictionsEnabled()) {
            return;
        }
        RestrictionPolicy policy = policy(player);
        boolean denied = event.isMounting()
                ? !policy.permits(RestraintAction.STEER_VEHICLE)
                : !policy.permits(RestraintAction.DISMOUNT);
        if (denied) {
            event.setCanceled(true);
            explain(player);
        }
    }

    /**
     * Attributes are not copied across a respawn, so the penalties are re-derived from what is worn.
     *
     * <p>Physical state survives a death by design: a subject does not stop being in handcuffs because
     * they died, and the custody question is a separate one that {@code CustodyService} answers.
     */
    @SubscribeEvent
    public static void onRespawn(PlayerEvent.PlayerRespawnEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            refresh(player);
        }
    }

    // ------------------------------------------------------------------ internals

    /**
     * The composed policy for this player: what is physically worn, folded with the lawful-arrest
     * phase.
     *
     * <p>Two sources, not three. Until M2.11 there was an interim third — the custody table, whose
     * pre-0.7.5 {@code RestraintType} was projected through the migration mapping so that a captive
     * taken by the legacy engine stayed as restricted as one wearing the new kind. Both engines were
     * live at once and a player restrained by either had to be restrained by both. The legacy engine
     * is gone, that projection with it, and the only route from an old custody row to physical state
     * is now {@code RestraintMigrationReconciler}, once, at world load.
     *
     * <p>The arrest fold stays, and stays here rather than in the gear: an arrest phase says
     * "restrained" without naming an item, and {@link RestrictionResolver} is what turns that into
     * the same action-typed answer worn gear gives.
     */
    public static RestrictionPolicy policy(@Nullable Player player) {
        if (!(player instanceof ServerPlayer server)) {
            return RestrictionPolicy.unrestricted();
        }
        PhysicalRestraintState state = RestraintService.state(server);
        return RestrictionResolver.resolve(state, null, ArrestStates.isRestrained(server));
    }

    /** The config-aware answer used by handlers that run before this class's LOW backstop. */
    public static boolean permits(@Nullable Player player, RestraintAction action) {
        return !restrictionsEnabled() || policy(player).permits(action);
    }

    private static boolean restrictionsEnabled() {
        try {
            return McaCrimeConfig.COMMON.restrainedPlayerRestrictions.get();
        } catch (IllegalStateException e) {
            return true;
        }
    }

    private static void deny(ICancellableEvent event, @Nullable Player player, RestraintAction action) {
        if (!(player instanceof ServerPlayer server) || !restrictionsEnabled()) {
            return;
        }
        if (policy(server).permits(action)) {
            return;
        }
        event.setCanceled(true);
        explain(server);
    }

    /**
     * Says why, at most once every couple of seconds.
     *
     * <p>A cancelled interaction with no explanation is indistinguishable from a broken one, and a
     * cancelled interaction that explains itself on every click is worse.
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
}
