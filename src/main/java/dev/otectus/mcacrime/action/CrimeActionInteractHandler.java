package dev.otectus.mcacrime.action;

import dev.otectus.mcacrime.McaCrime;
import dev.otectus.mcacrime.compat.McaCompat;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/** Compatibility fallback: Shift+interact opens the same server-issued menu used by the MCA bridge. */
@Mod.EventBusSubscriber(modid = McaCrime.MOD_ID)
public final class CrimeActionInteractHandler {
    private CrimeActionInteractHandler() {}

    @SubscribeEvent
    public static void onInteract(PlayerInteractEvent.EntityInteract event) {
        if (!(event.getEntity() instanceof ServerPlayer player) || event.getHand() != InteractionHand.MAIN_HAND
                || !player.isShiftKeyDown() || !event.getItemStack().isEmpty()
                || !McaCompat.isMcaVillager(event.getTarget())) return;
        if (CrimeActionService.openMenu(player, event.getTarget().getUUID())) {
            event.setCanceled(true);
            event.setCancellationResult(InteractionResult.SUCCESS);
        }
    }
}
