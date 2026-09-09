package dev.otectus.mcacrime.memory;

import dev.otectus.mcacrime.McaCrime;
import dev.otectus.mcacrime.action.CrimeActionService;
import dev.otectus.mcacrime.ai.CrimeReactionService;
import dev.otectus.mcacrime.ai.VictimReactionState;
import dev.otectus.mcacrime.compat.McaCompat;
import dev.otectus.mcacrime.dialogue.CrimeDialogueService;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;

/** Contextual refusal at the interaction boundary, with the reconciliation menu always reachable. */
@EventBusSubscriber(modid = McaCrime.MOD_ID)
public final class MemoryInteractionHandler {
    private MemoryInteractionHandler() {}
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onInteract(PlayerInteractEvent.EntityInteract event) {
        if (!(event.getEntity() instanceof ServerPlayer player) || !(event.getTarget() instanceof LivingEntity villager)
                || !McaCompat.isMcaVillager(villager)) return;
        var memories = VictimMemoryService.memories(player.getServer(), villager.getUUID(), player.getUUID());
        if (memories.isEmpty()) return;
        if (player.isShiftKeyDown() && player.getMainHandItem().isEmpty()) {
            if (event.getHand() == InteractionHand.OFF_HAND) {
                event.setCanceled(true); event.setCancellationResult(InteractionResult.SUCCESS);
                return;
            }
            if (CrimeActionService.openMenu(player, villager.getUUID())) {
                event.setCanceled(true); event.setCancellationResult(InteractionResult.SUCCESS);
            }
            return;
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
        player.sendSystemMessage(Component.translatable("mcacrime.apologize.hint"));
        if (strongest.fear() >= 0.45 && CrimeReactionService.stateOf(villager.getUUID()) == VictimReactionState.CALM)
            CrimeReactionService.trigger(player.serverLevel(), villager, player.getUUID(), VictimReactionState.FLEEING, null);
    }
}
