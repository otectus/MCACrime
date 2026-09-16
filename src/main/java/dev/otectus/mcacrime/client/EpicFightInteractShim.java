package dev.otectus.mcacrime.client;

import dev.otectus.mcacrime.McaCrime;
import dev.otectus.mcacrime.captivity.RestraintType;
import dev.otectus.mcacrime.compat.EpicFightCompat;
import dev.otectus.mcacrime.compat.McaCompat;
import dev.otectus.mcacrime.item.CrimeItems;
import net.minecraft.client.Minecraft;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.EntityHitResult;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.InputEvent;

/**
 * Gives the armed right-click back to the player when Epic Fight's battle mode has eaten it.
 *
 * <h2>What goes wrong without this</h2>
 *
 * <p>In battle mode, while the held weapon has a guard skill that can execute and the crosshair entity's
 * client-side {@code interact} returns anything but {@code PASS}, Epic Fight's {@code ControlEngine}
 * cancels {@link InputEvent.InteractionKeyMappingTriggered} for the use key — its default
 * {@code key_conflict_resolve_scope = INTERACTION}. Cancelling that event stops
 * {@code Minecraft.startUseItem}, and {@code startUseItem} is what sends {@code INTERACT_AT} and
 * {@code INTERACT}. No packet means no {@code PlayerInteractEvent.EntityInteract} on the server, so
 * {@code action/CrimeActionInteractHandler} never runs and neither does MCA's own screen. Nothing
 * server-side is cancelling anything; the interaction simply never leaves the client.
 *
 * <h2>Why it only ever acts on an already-cancelled event</h2>
 *
 * <p>{@code receiveCanceled = true} at {@code LOWEST}, and the first thing the decision asks is whether
 * the event <em>is</em> cancelled. An event that was not cancelled is being handled by vanilla a moment
 * later, exactly as it should be, and forwarding it here as well would send a second pair of packets:
 * two menus, or a menu and a gift. Acting only on the cancelled case makes a double interaction
 * impossible rather than unlikely.
 *
 * <h2>Why the empty hand is left alone</h2>
 *
 * <p>An empty-handed right-click opens MCA's interaction screen, which is MCA's business and not this
 * mod's to synthesise. Forwarding it would mean this mod deciding when another mod's UI opens, on the
 * client, against a key binding the player configured. The documented workaround is Epic Fight's own:
 * leave battle mode, or set {@code key_conflict_resolve_scope = NONE}.
 *
 * <h2>Why forwarding grants nothing</h2>
 *
 * <p>The packets sent here are the ones vanilla would have sent, so the server sees an ordinary
 * interaction and re-validates all of it — {@code CrimeActionService.openMenu} checks reach, line of
 * sight, the weapon rule, custody, child protection and cooldowns. A client that forwards a request it
 * should not have gains nothing it could not have got by leaving battle mode.
 */
@EventBusSubscriber(modid = McaCrime.MOD_ID, value = Dist.CLIENT)
public final class EpicFightInteractShim {

    /**
     * Minimum ticks between two forwards. Epic Fight can cancel the event on every key repeat while the
     * button is held, and the server's own cooldowns answer in chat — so an unthrottled shim spams a
     * refusal half a second long, twenty times a second.
     */
    static final long FORWARD_COOLDOWN_TICKS = 10L;

    /**
     * The last forwarded game tick. Half of {@code Long.MIN_VALUE} rather than all of it so that
     * {@code now - lastForwardTick} stays positive on the first call instead of overflowing into a
     * negative elapsed time that would veto every forward.
     */
    private static long lastForwardTick = Long.MIN_VALUE / 2;

    private EpicFightInteractShim() {
    }

    @SubscribeEvent(priority = EventPriority.LOWEST, receiveCanceled = true)
    public static void onInteractionKey(InputEvent.InteractionKeyMappingTriggered event) {
        if (!EpicFightCompat.isEpicFightLoaded()) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null || mc.gameMode == null
                || !(mc.hitResult instanceof EntityHitResult ehr)) {
            return;
        }
        Entity target = ehr.getEntity();
        InteractionHand hand = event.getHand();

        boolean holdingRestraint =
                CrimeItems.restraintFor(mc.player.getMainHandItem()) != RestraintType.NONE;
        // The server's own trigger settings, as they arrived with the weapon policy -- never the
        // client's COMMON file, which on a multiplayer server is not what the gate is evaluated
        // against. The server re-checks everything anyway, so a stale policy costs a refusal.
        boolean triggerApplies = crimeTriggerApplies(
                ClientWeaponPolicy.triggerEnabled(),
                ClientWeaponPolicy.triggerRequireSneak(),
                mc.player.isShiftKeyDown(),
                holdingRestraint,
                ClientWeaponPolicy.hasQualifyingDrawnWeapon(mc.player));

        long now = mc.level.getGameTime();
        if (!shouldForward(true, event.isCanceled(), event.isUseItem(), hand,
                McaCompat.isMcaVillager(target), triggerApplies, now - lastForwardTick)) {
            return;
        }

        // Exactly what Minecraft.startUseItem does for the entity branch, in the same order: the border
        // test, interactAt (which sends INTERACT_AT), then interact (which sends INTERACT) only if the
        // first did not consume, then the swing. Anything less sends a packet pair the server does not
        // expect; anything more is a second interaction.
        if (!mc.level.getWorldBorder().isWithinBounds(target.blockPosition())) {
            return;
        }
        InteractionResult result = mc.gameMode.interactAt(mc.player, target, ehr, hand);
        if (!result.consumesAction()) {
            result = mc.gameMode.interact(mc.player, target, hand);
        }
        if (result.consumesAction() && result.shouldSwing()) {
            mc.player.swing(hand);
        }
        lastForwardTick = now;
        McaCrime.LOGGER.debug("Epic Fight shim forwarded a cancelled use-key interaction with an MCA "
                + "villager (result {})", result);
    }

    /**
     * The whole decision, as data, so it can be read and tested without a client.
     *
     * <p>Every condition is a veto; none of them is a heuristic. Epic Fight must be installed (nothing
     * else cancels this event for this reason), the event must already be cancelled (see the class
     * note), it must be the use key rather than attack or pick-block, the hand must be the main one
     * (the off hand is not what battle mode consumes, and an off-hand forward would fire alongside a
     * main-hand one), the target must be an MCA villager, this mod's own trigger must actually apply to
     * the held item, and the throttle must have elapsed.
     */
    static boolean shouldForward(boolean epicFightLoaded, boolean canceled, boolean isUseItem,
                                 InteractionHand hand, boolean targetIsMcaVillager,
                                 boolean crimeTriggerApplies, long ticksSinceLastForward) {
        return epicFightLoaded
                && canceled
                && isUseItem
                && hand == InteractionHand.MAIN_HAND
                && targetIsMcaVillager
                && crimeTriggerApplies
                && ticksSinceLastForward >= FORWARD_COOLDOWN_TICKS;
    }

    /**
     * Whether the interaction is one MCA: Crime would have acted on had it reached the server.
     *
     * <p>A restraint bypasses the weapon trigger's own switches because capture is a separate mechanic
     * with its own handler and its own rules; the weapon path honours both the master switch and the
     * sneak requirement, so a server that has turned the trigger off does not get the packet forwarded
     * on its behalf.
     */
    static boolean crimeTriggerApplies(boolean triggerEnabled, boolean requireSneak, boolean sneaking,
                                       boolean holdingRestraint, boolean holdingQualifyingWeapon) {
        return holdingRestraint
                || (triggerEnabled && holdingQualifyingWeapon && (!requireSneak || sneaking));
    }

    /** Test and disconnect hook: the next world's game time is not this one's. */
    static void resetThrottle() {
        lastForwardTick = Long.MIN_VALUE / 2;
    }
}
