package dev.otectus.mcacrime.item;

import dev.otectus.mcacrime.McaCrime;
import dev.otectus.mcacrime.McaCrimeConfig;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Makes right-clicking somebody with a Sand Bottle throw it (0.7.2 §13.2).
 *
 * <p>Without this the item would lose the click. Vanilla asks the <em>entity</em> what a right-click
 * means before it asks the item, so MCA's conversation screen opens first and the bottle is never
 * thrown — which is precisely the behaviour the spec says must not happen.
 *
 * <p>Priority LOW, matching {@code CrimeActionInteractHandler}: restraints and captures run at NORMAL
 * and keep their claim on the click, and a cancelled event is not delivered here at all, so nothing
 * this mod does un-cancels another mod's refusal (invariant 16).
 */
@Mod.EventBusSubscriber(modid = McaCrime.MOD_ID)
public final class SandBottleInteractHandler {

    private SandBottleInteractHandler() {
    }

    @SubscribeEvent(priority = EventPriority.LOW)
    public static void onInteract(PlayerInteractEvent.EntityInteract event) {
        if (!McaCrimeConfig.COMMON.enableSandBottles.get()) {
            return;
        }
        ItemStack stack = event.getItemStack();
        if (!(stack.getItem() instanceof SandBottleItem)) {
            return;
        }
        if (event.getEntity() instanceof ServerPlayer player) {
            InteractionResult result = SandThrowService.throwBottle(player, event.getHand());
            if (result == InteractionResult.PASS) {
                return;
            }
            event.setCanceled(true);
            event.setCancellationResult(result);
            return;
        }
        if (event.getLevel().isClientSide()) {
            // Claim the click on the client too, so the villager screen does not flash open and shut
            // while the server's answer is in flight.
            event.setCanceled(true);
            event.setCancellationResult(InteractionResult.SUCCESS);
        }
    }
}
