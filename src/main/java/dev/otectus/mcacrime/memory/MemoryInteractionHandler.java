package dev.otectus.mcacrime.memory;

import dev.otectus.mcacrime.McaCrime;
import dev.otectus.mcacrime.McaCrimeConfig;
import dev.otectus.mcacrime.action.ActionResult;
import dev.otectus.mcacrime.action.ActionSessionManager;
import dev.otectus.mcacrime.action.CrimeActionIds;
import dev.otectus.mcacrime.action.CrimeActionService;
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
import net.minecraft.world.entity.LivingEntity;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

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
 */
@Mod.EventBusSubscriber(modid = McaCrime.MOD_ID)
public final class MemoryInteractionHandler {
    private MemoryInteractionHandler() {}

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onInteract(PlayerInteractEvent.EntityInteract event) {
        if (!(event.getEntity() instanceof ServerPlayer player) || !(event.getTarget() instanceof LivingEntity villager)
                || !McaCompat.isMcaVillager(villager)) return;
        long now = player.serverLevel().getGameTime();
        // Invariant 11: the companion hand path of a click already claimed is consumed, not re-routed.
        if (ApologyClaimLedger.isCompanionPath(player.getUUID(), villager.getUUID(), now)) {
            event.setCanceled(true);
            event.setCancellationResult(InteractionResult.SUCCESS);
            return;
        }
        var memories = VictimMemoryService.memories(player.getServer(), villager.getUUID(), player.getUUID());
        if (memories.isEmpty()) return;

        var decision = ApologyInteractionPolicy.route(facts(event, player, villager, now, true));
        String reasonKey = null;
        switch (decision.route()) {
            case EXECUTE -> {
                ActionResult result = CrimeActionService.startContextual(player, CrimeActionIds.APOLOGIZE, villager);
                if (result.accepted()) {
                    // The handler already said the apology was heard. Claim the click and stop here, so
                    // the next ordinary click behaves normally.
                    ApologyClaimLedger.claim(player.getUUID(), villager.getUUID(), now);
                    event.setCanceled(true);
                    event.setCancellationResult(InteractionResult.SUCCESS);
                    return;
                }
                // A refusal the facts could not see: say which one, then let the villager's own
                // reaction below have the last word.
                if (!"mcacrime.action.feedback_sent".equals(result.code())) reasonKey = result.code();
            }
            case REFUSE_WITH_REASON -> reasonKey = decision.reasonKey();
            case NOT_CLAIMED, PASS_THROUGH -> { }
        }

        var strongest = memories.stream().max(java.util.Comparator.comparingDouble(m -> m.fear() + m.anger())).orElseThrow();
        if (dev.otectus.mcacrime.detect.EntitySelectors.isResponder(villager)) return;
        if (strongest.fear() < 0.45 && strongest.anger() < 0.6) return;
        event.setCanceled(true); event.setCancellationResult(InteractionResult.SUCCESS);
        if (event.getHand() != InteractionHand.MAIN_HAND) return;
        var line = McaCrime.id(strongest.category().equals("FAMILY_HARM") ? "memory_family"
                : strongest.restitutionPaid() ? "memory_restitution" : strongest.fear() >= 0.45 ? "memory_fear" : "memory_anger");
        CrimeDialogueService.speak(villager, player, line,
                CrimeDialogueService.context(player.serverLevel(), villager, player, strongest.incident(), line));
        // Where conversation is refused, the player hears the true reason — the wait they are actually
        // in — instead of a generic hint, and never "they have no history with you".
        player.sendSystemMessage(Component.translatable(
                reasonKey == null || reasonKey.isEmpty() ? "mcacrime.apologize.hint" : reasonKey));
        if (strongest.fear() >= 0.45 && CrimeReactionService.stateOf(villager.getUUID()) == VictimReactionState.CALM)
            CrimeReactionService.trigger(player.serverLevel(), villager, player.getUUID(), VictimReactionState.FLEEING, null);
    }

    /** Live world state, reduced to the plain facts the routing table is written against. */
    private static ApologyInteractionPolicy.Facts facts(PlayerInteractEvent.EntityInteract event, ServerPlayer player,
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
                event.isCanceled(),
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
