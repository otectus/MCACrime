package dev.otectus.mcacrime.action;

import dev.otectus.mcacrime.McaCrime;
import dev.otectus.mcacrime.McaCrimeConfig;
import dev.otectus.mcacrime.captivity.RestraintType;
import dev.otectus.mcacrime.compat.McaCompat;
import dev.otectus.mcacrime.item.CrimeItems;
import dev.otectus.mcacrime.item.weapon.WeaponDetector;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;

/**
 * The weapon-in-hand trigger (0.5.0): right-clicking an MCA villager while armed opens the same
 * server-issued menu the MCA screen button asks for.
 *
 * <p>It replaces the sneak + empty-hand fallback, which was a gesture nobody discovers and which
 * competed with every ordinary interaction a villager has. Drawing a weapon on somebody is the one
 * gesture that already means what this menu is for.
 *
 * <p>Priority is LOW so restraints keep their claim on the interaction: {@code CaptureInteractHandler}
 * and the restraint handlers run at NORMAL and cancel, and cancelled events are not delivered here.
 * The stack is re-checked for a restraint anyway, because a capture that was refused for its own
 * reasons must not fall through into the crime menu.
 *
 * <p>Both sides cancel with SUCCESS. The server one is what actually opens the menu; the client one
 * exists so a bow does not begin drawing under the player's hands while the packet is in flight. The
 * two run the same rule against COMMON config, which is not synced — a client whose config differs
 * from the server's mispredicts the swing, and the server's answer is still the one that counts.
 *
 * <p>Documented side effect: while the trigger is on, right-click gifting a weapon to an MCA villager
 * is pre-empted. Blacklist the item under {@code [weapons]} or turn the trigger off to gift it.
 */
@EventBusSubscriber(modid = McaCrime.MOD_ID)
public final class CrimeActionInteractHandler {
    private CrimeActionInteractHandler() {}

    @SubscribeEvent(priority = EventPriority.LOW)
    public static void onInteract(PlayerInteractEvent.EntityInteract event) {
        McaCrimeConfig.Common c = McaCrimeConfig.COMMON;
        if (!c.weaponTriggerEnabled.get()) return;
        if (event.getHand() == InteractionHand.OFF_HAND && !c.weaponTriggerAllowOffHand.get()) return;
        if (c.weaponTriggerRequireSneak.get() && !event.getEntity().isShiftKeyDown()) return;
        if (!McaCompat.isMcaVillager(event.getTarget())) return;

        ItemStack stack = event.getItemStack();
        if (CrimeItems.restraintFor(stack) != RestraintType.NONE) return;
        if (!WeaponDetector.isWeapon(stack)) return;

        if (event.getEntity() instanceof ServerPlayer player) {
            if (CrimeActionService.openMenu(player, event.getTarget().getUUID())) {
                event.setCanceled(true);
                event.setCancellationResult(InteractionResult.SUCCESS);
            }
            return;
        }
        if (event.getLevel().isClientSide()) {
            event.setCanceled(true);
            event.setCancellationResult(InteractionResult.SUCCESS);
        }
    }
}
