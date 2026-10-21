package dev.otectus.mcacrime.memory;

import dev.otectus.mcacrime.McaCrime;
import dev.otectus.mcacrime.McaCrimeConfig;
import dev.otectus.mcacrime.action.ActionResult;
import dev.otectus.mcacrime.action.ActionSessionManager;
import dev.otectus.mcacrime.action.CrimeActionIds;
import dev.otectus.mcacrime.action.CrimeActionService;
import dev.otectus.mcacrime.action.McaVillagerClicks;
import dev.otectus.mcacrime.ai.CrimeReactionService;
import dev.otectus.mcacrime.ai.VictimReactionState;
import dev.otectus.mcacrime.captivity.CustodyRegistry;
import dev.otectus.mcacrime.compat.McaCompat;
import dev.otectus.mcacrime.dialogue.CrimeDialogueService;
import dev.otectus.mcacrime.item.weapon.WeaponDetector;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

/**
 * The one place a world interaction with a remembered villager is routed (0.7.2 §12).
 *
 * <p>Reconciliation used to hide behind sneak + empty hand, a gesture nobody discovers and one that
 * villager-pickup add-ons already bind. The default gesture is now the obvious one: right-click the
 * villager you wronged while <em>not</em> sneaking, with an empty main hand and no weapon in the off
 * hand, and the apology happens directly instead of opening a screen to pick the same word again.
 *
 * <p><b>What happened to the sneak path.</b> Sneak + empty hand no longer opens the Crime menu here.
 * Sneaking is a deliberate gesture other mods claim, and spec §12.2 requires Crime not to take it.
 * The menu keeps three other ways in — the {@code key.mcacrime.crime_menu} keybind, the Crime button
 * on MCA's own interaction screen, and the armed right-click trigger in
 * {@code CrimeActionInteractHandler} — so nothing became unreachable, and the apology route is the
 * gesture rather than the menu.
 *
 * <p>Priority stays LOWEST and cancelled events are still not received: a committed flow that already
 * claimed the click (restraint, capture, the armed menu trigger at LOW) keeps it, and invariant 16
 * means nothing here ever un-cancels another mod's event. Every routing decision lives in the pure
 * {@link ApologyInteractionPolicy}; this class only observes the world and obeys.
 *
 * <p>Port-only (0.7.9): MCA 1.21.1 opens its screen from {@code interactAt}, so the precise
 * {@code EntityInteractSpecific} is routed here as well, once per click ({@link McaVillagerClicks}).
 */
@EventBusSubscriber(modid = McaCrime.MOD_ID)
public final class MemoryInteractionHandler {
    private MemoryInteractionHandler() {}

    /** Server-side slack on top of the player's own entity reach, for movement and latency. */
    private static final double REACH_PADDING = 3.0D;

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onInteract(PlayerInteractEvent.EntityInteract event) {
        var echo = McaVillagerClicks.echo(McaVillagerClicks.Route.MEMORY, event.getEntity(), event.getTarget(),
                event.getHand());
        if (echo != null ? echo.consumed()
                : route(event.getEntity(), event.getTarget(), event.getHand(), event.isCanceled())) {
            event.setCanceled(true);
            event.setCancellationResult(InteractionResult.SUCCESS);
        }
    }

    /** The precise half, which is the one that decides for an MCA villager on this port. */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onInteractSpecific(PlayerInteractEvent.EntityInteractSpecific event) {
        if (!McaVillagerClicks.decidesHere(event)) return;
        boolean consumed = route(event.getEntity(), event.getTarget(), event.getHand(), event.isCanceled());
        McaVillagerClicks.decided(McaVillagerClicks.Route.MEMORY, event.getEntity(), event.getTarget(),
                event.getHand(), consumed ? InteractionResult.SUCCESS : null);
        if (consumed) {
            event.setCanceled(true);
            event.setCancellationResult(InteractionResult.SUCCESS);
        }
    }

    /** Routes one click, and answers whether it is consumed. */
    private static boolean route(Player clicker, Entity target, InteractionHand hand, boolean eventCanceled) {
        if (!(clicker instanceof ServerPlayer player) || !(target instanceof LivingEntity villager)
                || !McaCompat.isMcaVillager(villager)) return false;
        // 0.7.7: a click is only a conversation with a villager the player can actually reach. Another
        // mod posting this event for a distant or cross-dimension villager must not be refused, blamed or
        // turned into an apology on that player's behalf.
        if (!withinReach(player, villager)) return false;
        long now = player.serverLevel().getGameTime();
        // Invariant 11: the companion hand path of a click already claimed is consumed, not re-routed.
        if (ApologyClaimLedger.isCompanionPath(player.getUUID(), villager.getUUID(), now)) {
            return true;
        }
        var memories = VictimMemoryService.memories(player.getServer(), villager.getUUID(), player.getUUID());
        if (memories.isEmpty()) return false;

        var decision = ApologyInteractionPolicy.route(facts(eventCanceled, player, villager, now, true));
        String reasonKey = null;
        switch (decision.route()) {
            case EXECUTE -> {
                ActionResult result = CrimeActionService.startContextual(player, CrimeActionIds.APOLOGIZE, villager);
                if (result.accepted()) {
                    // The handler already said the apology was heard. Claim the click and stop here, so
                    // the next ordinary click behaves normally.
                    ApologyClaimLedger.claim(player.getUUID(), villager.getUUID(), now);
                    return true;
                }
                // A refusal the facts could not see: say which one, then let the villager's own
                // reaction below have the last word.
                if (!"mcacrime.action.feedback_sent".equals(result.code())) reasonKey = result.code();
            }
            case REFUSE_WITH_REASON -> reasonKey = decision.reasonKey();
            case NOT_CLAIMED, PASS_THROUGH -> { }
        }

        if (dev.otectus.mcacrime.detect.EntitySelectors.isResponder(villager)) return false;
        // 0.7.7: the gate reads the apology flag. Before, fear and anger alone refused, so an accepted
        // apology for one punch still cancelled every later click.
        var gate = VictimMemoryService.conversation(player.getServer(), villager.getUUID(), player.getUUID());
        if (gate.allows() || gate.deciding() == null) return false;
        if (hand != InteractionHand.MAIN_HAND) return true;
        VictimCrimeMemory strongest = gate.deciding();
        double decay = McaCrimeConfig.COMMON.memoryDecayMultiplier.get();
        boolean afraid = strongest.fearAt(player.getServer().overworld().getGameTime(), decay)
                >= ConversationGate.FEAR_BLOCK;
        if (gate.verdict() == ConversationGate.Verdict.WARY) {
            // The apology was heard and accepted; what remains is a grave memory that has to fade.
            var line = dev.otectus.mcacrime.dialogue.DialogueEvents.MEMORY_APOLOGIZED;
            CrimeDialogueService.speak(villager, player, line,
                    CrimeDialogueService.context(player.serverLevel(), villager, player, strongest.incident(), line));
            long days = ConversationGate.daysRemaining(gate.ticksRemaining());
            player.sendSystemMessage(days == Long.MAX_VALUE
                    ? Component.translatable("mcacrime.apologize.still_wary_indefinite")
                    : Component.translatable("mcacrime.apologize.still_wary", days));
        } else {
            var line = McaCrime.id(strongest.category() == CrimeMemoryCategory.FAMILY_HARM ? "memory_family"
                    : strongest.restitutionPaid() ? "memory_restitution" : afraid ? "memory_fear" : "memory_anger");
            CrimeDialogueService.speak(villager, player, line,
                    CrimeDialogueService.context(player.serverLevel(), villager, player, strongest.incident(), line));
            // Where conversation is refused, the player hears the true reason — the wait they are actually
            // in — and never "they have no history with you". The how-to hint is only for a click that
            // was not the apology gesture at all; a gesture that was refused already said why.
            String message = refusalMessage(reasonKey, decision.route(),
                    VictimMemoryService.apologyStatus(player.getServer(), villager.getUUID(), player.getUUID(), now),
                    McaCrimeConfig.COMMON.emptyHandApologyMode.get());
            if (message != null) player.sendSystemMessage(Component.translatable(message));
        }
        if (afraid && CrimeReactionService.stateOf(villager.getUUID()) == VictimReactionState.CALM)
            CrimeReactionService.trigger(player.serverLevel(), villager, player.getUUID(), VictimReactionState.FLEEING, null);
        return true;
    }

    /**
     * The one line that explains a refusal (0.7.7): the reason a usable gesture was refused, else the
     * true apology status, else the how-to hint when an apology is available but the click was not the
     * gesture. Null when there is nothing true to add to the villager's own line.
     */
    public static String refusalMessage(String reasonKey, ApologyInteractionPolicy.Route route, ApologyStatus status,
                                        ApologyInteractionPolicy.Mode mode) {
        if (reasonKey != null && !reasonKey.isEmpty()) return reasonKey;
        boolean gestureUsed = route == ApologyInteractionPolicy.Route.EXECUTE
                || route == ApologyInteractionPolicy.Route.REFUSE_WITH_REASON;
        return switch (status) {
            // The hint describes the empty-hand gesture, so it is wrong in MENU_ONLY mode.
            case READY -> gestureUsed || mode != ApologyInteractionPolicy.Mode.CONTEXTUAL_DIRECT
                    ? null : "mcacrime.apologize.hint";
            case GIVE_SPACE, COOLDOWN, DISABLED -> status.reason();
            case NOT_NEEDED, ALREADY_APOLOGIZED -> null;
        };
    }

    /** Same level, and within the player's entity interaction range plus {@link #REACH_PADDING}. */
    private static boolean withinReach(ServerPlayer player, LivingEntity villager) {
        return player.level() == villager.level() && player.canInteractWithEntity(villager, REACH_PADDING);
    }

    /** Live world state, reduced to the plain facts the routing table is written against. */
    private static ApologyInteractionPolicy.Facts facts(boolean eventCanceled, ServerPlayer player,
                                                        LivingEntity villager, long now, boolean hasGrievance) {
        // A sword in the backpack is not a drawn weapon and an off-hand torch is not a threat, so only
        // the off-hand stack itself is classified here; the main hand has to be empty anyway.
        boolean offhandWeapon = WeaponDetector.isWeapon(player.getOffhandItem());
        boolean competingFlow = ActionSessionManager.forActor(player.getUUID()).isPresent()
                || ActionSessionManager.targetLocked(villager.getUUID())
                || CustodyRegistry.isCaptive(player.getServer(), villager.getUUID())
                || CustodyRegistry.isCaptive(player.getServer(), player.getUUID());
        boolean activeThreat = ActionSessionManager.activeCoerciveAgainst(villager.getUUID()).isPresent();
        return new ApologyInteractionPolicy.Facts(
                eventCanceled,
                player.isShiftKeyDown(),
                player.getMainHandItem().isEmpty(),
                offhandWeapon,
                villager.isAlive() && !villager.isRemoved(),
                !McaCompat.isVillagerSleeping(villager),
                hasGrievance,
                VictimMemoryService.apologyStatus(player.getServer(), villager.getUUID(), player.getUUID(), now),
                competingFlow,
                activeThreat,
                McaCrimeConfig.COMMON.emptyHandApologyMode.get());
    }
}
