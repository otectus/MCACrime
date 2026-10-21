package dev.otectus.mcacrime.action;

import dev.otectus.mcacrime.McaCrime;
import dev.otectus.mcacrime.McaCrimeConfig;
import dev.otectus.mcacrime.compat.McaCompat;
import dev.otectus.mcacrime.item.CrimeItems;
import dev.otectus.mcacrime.item.weapon.WeaponDetector;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
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
 *
 * <p>Port-only (0.7.9): MCA 1.21.1 opens its screen from {@code interactAt}, so on the server the
 * precise {@code EntityInteractSpecific} is routed here as well, once per click
 * ({@link McaVillagerClicks}).
 */
@EventBusSubscriber(modid = McaCrime.MOD_ID)
public final class CrimeActionInteractHandler {
    private CrimeActionInteractHandler() {}

    @SubscribeEvent(priority = EventPriority.LOW)
    public static void onInteract(PlayerInteractEvent.EntityInteract event) {
        if (!triggers(event.getEntity(), event.getTarget(), event.getHand(), event.getItemStack())) return;

        if (event.getEntity() instanceof ServerPlayer player) {
            var echo = McaVillagerClicks.echo(McaVillagerClicks.Route.ARMED_MENU, player, event.getTarget(),
                    event.getHand());
            if (echo != null ? echo.consumed() : CrimeActionService.openMenu(player, event.getTarget().getUUID())) {
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

    /** The precise half, which is the one that decides for an MCA villager on this port. */
    @SubscribeEvent(priority = EventPriority.LOW)
    public static void onInteractSpecific(PlayerInteractEvent.EntityInteractSpecific event) {
        if (!McaVillagerClicks.decidesHere(event)
                || !triggers(event.getEntity(), event.getTarget(), event.getHand(), event.getItemStack())) return;
        boolean opened = CrimeActionService.openMenu((ServerPlayer) event.getEntity(), event.getTarget().getUUID());
        McaVillagerClicks.decided(McaVillagerClicks.Route.ARMED_MENU, event.getEntity(), event.getTarget(),
                event.getHand(), opened ? InteractionResult.SUCCESS : null);
        if (opened) {
            event.setCanceled(true);
            event.setCancellationResult(InteractionResult.SUCCESS);
        }
    }

    /** Whether this click is the armed trigger at all, on either side. */
    private static boolean triggers(Player player, Entity target, InteractionHand hand, ItemStack stack) {
        McaCrimeConfig.Common c = McaCrimeConfig.COMMON;
        if (!c.weaponTriggerEnabled.get()) return false;
        if (hand == InteractionHand.OFF_HAND && !c.weaponTriggerAllowOffHand.get()) return false;
        if (c.weaponTriggerRequireSneak.get() && !player.isShiftKeyDown()) return false;
        if (!McaCompat.isMcaVillager(target)) return false;
        // Defer to restraint/RestraintInteractHandler: a restraint item aimed at a villager is an
        // application, never a weapon threat, and the two handlers must not both claim it.
        if (CrimeItems.familyFor(stack).isPresent()) return false;
        return WeaponDetector.isWeapon(stack);
    }
}
