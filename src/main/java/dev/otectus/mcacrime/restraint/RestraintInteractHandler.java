package dev.otectus.mcacrime.restraint;

import dev.otectus.mcacrime.McaCrime;
import dev.otectus.mcacrime.item.CrimeItems;
import dev.otectus.mcacrime.item.lock.LockpickItem;
import dev.otectus.mcacrime.enforcement.RestraintHandlers;
import dev.otectus.mcacrime.state.world.CrimeWorldData;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import javax.annotation.Nullable;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The one entry point for every restraint interaction (0.7.5 M2.5), replacing
 * {@code captivity/CaptureInteractHandler}.
 *
 * <h2>The priority order</h2>
 * Right-clicking a person can mean several things at once, and the specification flags the empty
 * hand as genuinely ambiguous. The order is fixed, documented, and the same everywhere:
 * <ol>
 *   <li><b>operator tool</b> — unmistakable, and refused outright without authority;</li>
 *   <li><b>frisking box</b> — an explicit container aimed at a person;</li>
 *   <li><b>restraint item</b> — putting something on;</li>
 *   <li><b>matching key</b> — taking something off;</li>
 *   <li><b>lockpick</b> — working on something that will not simply come off;</li>
 *   <li><b>empty hand while crouching</b> — keyless removal;</li>
 *   <li><b>empty hand, standing</b> — start an escort;</li>
 *   <li>otherwise fall through to MCA's own interaction.</li>
 * </ol>
 *
 * <p>The empty-hand ambiguity is resolved by the crouch, and the ordering rule that matters is this:
 * <b>a release request never starts an escort</b>. Removal is checked before escort at every level —
 * a key outranks it, and crouching outranks it — so a player reaching to free somebody cannot
 * accidentally take them into custody instead.
 *
 * <p>{@link #route} is pure and is the whole decision. The event handlers below only gather the
 * facts it needs, which is what lets the order be asserted without a server and what stops the same
 * precedence from being re-derived, differently, in the two Forge events that carry an interaction.
 */
@Mod.EventBusSubscriber(modid = McaCrime.MOD_ID)
public final class RestraintInteractHandler {

    private RestraintInteractHandler() {
    }

    /** What an interaction was for. */
    public enum Route {
        /** Nothing of ours; MCA and vanilla see the interaction untouched. */
        PASS,
        OPERATOR_TOOL,
        APPLY_RESTRAINT,
        KEY_REMOVAL,
        LOCKPICK,
        KEYLESS_REMOVAL,
        ESCORT_START
    }

    /**
     * The priority order, as a function of what the server can see.
     *
     * <p>Every argument is something the server established for itself: what is in the hand, whether
     * the subject is actually wearing anything, whether the actor is crouching. Nothing here is taken
     * from the client's claim about its own intent.
     */
    public static Route route(boolean operatorTool, boolean restraintItem,
                              boolean matchingKey, boolean lockpick, boolean emptyHand,
                              boolean crouching, boolean subjectRestrained) {
        if (operatorTool) {
            return Route.OPERATOR_TOOL;
        }
        if (restraintItem) {
            return Route.APPLY_RESTRAINT;
        }
        if (matchingKey && subjectRestrained) {
            return Route.KEY_REMOVAL;
        }
        if (lockpick && subjectRestrained) {
            return Route.LOCKPICK;
        }
        if (emptyHand && crouching && subjectRestrained) {
            return Route.KEYLESS_REMOVAL;
        }
        if (emptyHand && !crouching) {
            return Route.ESCORT_START;
        }
        return Route.PASS;
    }

    // --- Forge entry points -------------------------------------------------------------------------

    /**
     * The precise form: this one carries where on the body the interaction landed.
     *
     * <p>Vanilla tries {@code interactAt} first and only falls back to {@code interact} if it did not
     * succeed, so this is the event that normally decides. The local position is remembered for the
     * same tick, because the fallback event does not carry one and guessing the arms for a hit that
     * was clearly aimed at the head is exactly the region confusion M2.4 exists to remove.
     */
    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onInteractSpecific(PlayerInteractEvent.EntityInteractSpecific event) {
        if (!(event.getEntity() instanceof ServerPlayer actor)) {
            return; // server-authoritative; the client fire decides nothing
        }
        Vec3 local = event.getLocalPos();
        rememberAim(actor, local, actor.level().getGameTime());
        if (handle(actor, event.getTarget(), event.getItemStack(), event.getHand(),
                local == null ? null : local.y)) {
            event.setCanceled(true);
            event.setCancellationResult(InteractionResult.SUCCESS);
        }
    }

    /** The fallback form, using the aim remembered from the precise event in the same tick. */
    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onInteract(PlayerInteractEvent.EntityInteract event) {
        if (!(event.getEntity() instanceof ServerPlayer actor)) {
            return;
        }
        if (handle(actor, event.getTarget(), event.getItemStack(), event.getHand(),
                rememberedAim(actor, actor.level().getGameTime()))) {
            event.setCanceled(true);
            event.setCancellationResult(InteractionResult.SUCCESS);
        }
    }

    /**
     * Does the work, and reports whether the interaction was consumed.
     *
     * <p>Consumed means MCA never sees it. Only a route that actually did something consumes: a
     * refused application leaves the interaction alone rather than swallowing it silently, so a player
     * holding cuffs in a village can still talk to a villager when the cuffs cannot go on.
     */
    private static boolean handle(ServerPlayer actor, @Nullable net.minecraft.world.entity.Entity target,
                                  ItemStack stack, net.minecraft.world.InteractionHand hand,
                                  @Nullable Double aimHeight) {
        if (!(target instanceof LivingEntity subject) || !RestraintService.restrainable(subject)) {
            return false;
        }
        CrimeWorldData data = RestraintService.data(subject);
        PhysicalRestraintState state = data == null ? null : data.physicalRestraint(subject.getUUID());
        boolean restrained = state != null && state.restrained();

        Optional<RestraintFamily> keyOpens = CrimeItems.keyOpens(stack);
        boolean matchingKey = keyOpens.isPresent() && state != null
                && wearsFamily(state, keyOpens.get());

        Route route = route(
                RemovalService.isOperatorTool(stack),
                CrimeItems.familyFor(stack).isPresent(),
                matchingKey,
                stack.getItem() instanceof LockpickItem,
                stack.isEmpty(),
                actor.isCrouching(),
                restrained);

        RigProfile rig = RestraintService.rig(subject);
        RestraintSlot slot = aimedSlot(aimHeight, subject, rig, state, route);

        // This handler runs before the generic LOW-priority restriction backstop, so it must not
        // commit a hand action and only then let that backstop cancel the event. Self-removal and
        // self-lockpicking remain protected escape actions; care from another actor is routed by its
        // own service and never reaches one of these restraint routes.
        if (!actorMayUseHands(actor, subject, route)) {
            return false;
        }

        return switch (route) {
            case OPERATOR_TOOL -> operatorTool(actor, subject, slot);
            case APPLY_RESTRAINT -> applyOrChannel(actor, subject, hand, slot, stack);
            case KEY_REMOVAL, KEYLESS_REMOVAL -> remove(actor, subject, slot, stack, route);
            // The pick opens a server-owned session against this exact worn instance (M3.3). It never
            // removes anything here: winning the session is what does that, on the server.
            case LOCKPICK -> dev.otectus.mcacrime.lockpick.LockpickService
                    .beginRestraint(actor, subject, slot);
            // Taking hold of somebody already restrained, with an empty hand (M4.3).
            case ESCORT_START -> escortStart(actor, subject, restrained);
            case PASS -> false;
        };
    }

    /** Whether the chosen route is a permitted hand action for this actor. */
    static boolean actorMayUseHands(ServerPlayer actor, LivingEntity subject, Route route) {
        if (actor.getUUID().equals(subject.getUUID())
                && (route == Route.KEY_REMOVAL || route == Route.KEYLESS_REMOVAL
                || route == Route.LOCKPICK || route == Route.OPERATOR_TOOL)) {
            return RestrictionResolver.allows(RestraintHandlers.policy(actor),
                    ProtectedAction.SELF_ESCAPE);
        }
        if (!RestraintHandlers.permits(actor, RestraintAction.INTERACT_ENTITY)) {
            return false;
        }
        return switch (route) {
            case OPERATOR_TOOL, APPLY_RESTRAINT, KEY_REMOVAL, LOCKPICK ->
                    RestraintHandlers.permits(actor, RestraintAction.USE_ITEM);
            case KEYLESS_REMOVAL, ESCORT_START, PASS -> true;
        };
    }

    /**
     * Puts the held restraint on, at once or through the application channel.
     *
     * <p>A channel is opened only when {@code restraints.application.channelTicks} asks for one and
     * somebody else is being restrained; putting a restraint on yourself is consent, not a struggle. A
     * channel is opened only for an application that would stand right now, so a right-click that
     * cannot succeed still falls through to MCA rather than starting a bar that is bound to fail.
     */
    private static boolean applyOrChannel(ServerPlayer actor, LivingEntity subject,
                                          net.minecraft.world.InteractionHand hand,
                                          @Nullable RestraintSlot slot, ItemStack stack) {
        boolean self = actor.getUUID().equals(subject.getUUID());
        if (self || dev.otectus.mcacrime.action.handler.RestrainActionHandler.channelTicks() <= 0) {
            return RestraintService.apply(actor, subject, hand, slot,
                    AppliedRestraint.ApplicationContext.UNLAWFUL).applied();
        }
        if (slot == null
                || RestraintService.evaluate(actor, subject, stack, slot) != ApplicationTransaction.Refusal.NONE) {
            return false;
        }
        return dev.otectus.mcacrime.action.handler.RestrainActionHandler.beginChannel(actor, subject, hand,
                slot, AppliedRestraint.ApplicationContext.UNLAWFUL, UUID.randomUUID());
    }

    /**
     * Removes the restraint at {@code slot}, if the route's own precondition still holds.
     *
     * <p>Re-checked here rather than trusted from {@link #route}, because the route was decided from
     * "is this subject wearing anything at all" and the removal is about one specific slot.
     */
    private static boolean remove(ServerPlayer actor, LivingEntity subject, @Nullable RestraintSlot slot,
                                  ItemStack tool, Route route) {
        if (slot == null) {
            return false;
        }
        PhysicalRestraintState state = RestraintService.state(subject);
        RemovalService.Refusal refusal = RemovalService.check(state, slot, tool, false);
        if (refusal != RemovalService.Refusal.NONE) {
            return false;
        }
        RemovalService.Reason reason = route == Route.KEY_REMOVAL
                ? RemovalService.Reason.KEY
                : RemovalService.Reason.KEYLESS;
        return RemovalService.remove(subject, slot, reason, actor).removed();
    }

    /**
     * An empty hand on somebody already restrained: lead them (M4.3).
     *
     * <p>Only a restrained subject, and only when this actor is leading nobody else. Both conditions
     * are what keep the empty hand usable: an unrestrained villager is being talked to, and an actor
     * who is already leading a prisoner is aiming at a seat, which
     * {@code tether/TetherInteractHandler} handles one priority level down.
     *
     * <p>The tether is an {@code ESCORT}, so it owes nobody a chain: taking hold of somebody with your
     * hands costs no item, and letting go of them must not mint one.
     */
    private static boolean escortStart(ServerPlayer actor, LivingEntity subject, boolean restrained) {
        if (!restrained || subject.getUUID().equals(actor.getUUID())) {
            return false;
        }
        CrimeWorldData data = RestraintService.data(subject);
        if (data == null
                || !dev.otectus.mcacrime.tether.TetherService.index(data)
                        .forHolder(actor.getUUID()).isEmpty()) {
            return false;
        }
        boolean took = dev.otectus.mcacrime.tether.TetherService.escort(actor, subject).isPresent();
        if (took) {
            dev.otectus.mcacrime.audio.CrimeSounds.chainAttached(subject, true);
        }
        return took;
    }

    /** The operator route: authority first, then clear that one slot. */
    private static boolean operatorTool(ServerPlayer actor, LivingEntity subject,
                                        @Nullable RestraintSlot slot) {
        if (!RemovalService.operatorAuthorised(actor)) {
            return false;
        }
        if (slot == null) {
            return false;
        }
        return RemovalService.remove(subject, slot, RemovalService.Reason.CREATIVE, null).removed();
    }

    /**
     * Which slot the interaction is about.
     *
     * <p>For an application the aim decides, because the point of aiming is choosing where the
     * restraint goes. For a removal the aim decides too, but an aim that resolves to an empty slot
     * falls back to the one occupied slot when there is exactly one — a player reaching to unlock
     * somebody's only restraint should not have to aim at it precisely, and with more than one worn
     * the aim is the only thing that can disambiguate.
     */
    @Nullable
    private static RestraintSlot aimedSlot(@Nullable Double aimHeight, LivingEntity subject,
                                           RigProfile rig, @Nullable PhysicalRestraintState state,
                                           Route route) {
        BodyRegionResolver.Region region = aimHeight == null
                ? BodyRegionResolver.Region.unavailable(BodyRegionResolver.Unavailable.NO_RIG)
                : BodyRegionResolver.resolve(aimHeight, subject.getBoundingBox().getYsize(), rig);
        RestraintSlot aimed = region.slot().orElse(null);
        if (route == Route.APPLY_RESTRAINT) {
            return aimed;
        }
        if (aimed != null && state != null && state.occupied(aimed)) {
            return aimed;
        }
        return state == null ? aimed : soleOccupied(state).orElse(aimed);
    }

    /** The single occupied slot, when there is exactly one. Ambiguity is never resolved by guessing. */
    private static Optional<RestraintSlot> soleOccupied(PhysicalRestraintState state) {
        RestraintSlot found = null;
        for (RestraintSlot slot : RestraintSlot.values()) {
            if (state.occupied(slot)) {
                if (found != null) {
                    return Optional.empty();
                }
                found = slot;
            }
        }
        return Optional.ofNullable(found);
    }

    private static boolean wearsFamily(PhysicalRestraintState state, RestraintFamily family) {
        for (RestraintSlot slot : RestraintSlot.values()) {
            boolean match = state.slot(slot)
                    .flatMap(AppliedRestraint::definition)
                    .flatMap(RestraintDefinition::family)
                    .filter(family::equals)
                    .isPresent();
            if (match) {
                return true;
            }
        }
        return false;
    }

    // --- the one-tick aim memory ---------------------------------------------------------------------

    private record Aim(double height, long tick) {
    }

    /** Bounded by {@link #MAX_AIMS} and by the one-tick lifetime. Transient, server thread only. */
    private static final Map<UUID, Aim> AIMS = new LinkedHashMap<>();

    private static final int MAX_AIMS = 128;

    private static void rememberAim(ServerPlayer actor, @Nullable Vec3 local, long tick) {
        if (local == null) {
            return;
        }
        if (AIMS.size() > MAX_AIMS) {
            AIMS.entrySet().removeIf(entry -> tick - entry.getValue().tick() > 1L);
        }
        AIMS.put(actor.getUUID(), new Aim(local.y, tick));
    }

    @Nullable
    private static Double rememberedAim(ServerPlayer actor, long tick) {
        Aim aim = AIMS.get(actor.getUUID());
        return aim != null && tick - aim.tick() <= 1L ? aim.height() : null;
    }

    /** Forgets every remembered aim. Server stop, and the start of each test. */
    public static void clearAims() {
        AIMS.clear();
    }
}
