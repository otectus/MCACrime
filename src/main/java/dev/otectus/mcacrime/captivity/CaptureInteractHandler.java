package dev.otectus.mcacrime.captivity;

import dev.otectus.mcacrime.McaCrime;
import dev.otectus.mcacrime.compat.McaCompat;
import dev.otectus.mcacrime.item.CrimeItems;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Right-click-with-a-restraint capture entry point (spec §8.2, §5.3 {@code EntityInteract}). Server-side
 * only; kept out of {@code CrimeDetectionHandlers} so detection and capture stay decoupled. On a valid
 * target it starts the channel via {@link CaptureService} and swallows the interaction so MCA's villager GUI
 * doesn't open.
 */
@Mod.EventBusSubscriber(modid = McaCrime.MOD_ID)
public final class CaptureInteractHandler {

    private CaptureInteractHandler() {
    }

    @SubscribeEvent
    public static void onEntityInteract(PlayerInteractEvent.EntityInteract event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return; // server-authoritative; ignore the client-side fire
        }
        RestraintType restraint = CrimeItems.restraintFor(event.getItemStack());
        if (restraint == RestraintType.NONE) {
            return;
        }
        if (!(event.getTarget() instanceof LivingEntity target)) {
            return;
        }
        if (!(target instanceof ServerPlayer) && !McaCompat.isMcaVillager(target)) {
            return; // only players and MCA villagers are valid capture targets
        }
        if (CaptureService.tryBeginCapture(player, target, restraint)) {
            event.setCanceled(true); // swallow the vanilla/MCA interaction (no villager GUI)
            event.setCancellationResult(InteractionResult.SUCCESS);
        }
    }
}
