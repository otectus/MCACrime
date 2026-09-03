package dev.otectus.mcacrime.jail;

import dev.otectus.mcacrime.McaCrime;
import dev.otectus.mcacrime.state.CrimeCapabilities;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * CONTAINMENT-mode block protection (spec §7.3): a jailed player cannot break blocks inside their jail
 * region, so they can't mine out. PHYSICAL mode allows it (a genuine breakout, handled by {@link JailConfine}).
 * Cheap: the not-jailed check short-circuits for essentially every break in the game.
 */
@Mod.EventBusSubscriber(modid = McaCrime.MOD_ID)
public final class ContainmentHandler {

    private ContainmentHandler() {
    }

    @SubscribeEvent
    public static void onBlockBreak(BlockEvent.BreakEvent event) {
        if (!(event.getPlayer() instanceof ServerPlayer player)) {
            return;
        }
        CrimeCapabilities.get(player).ifPresent(data -> {
            JailState jail = data.getJail();
            if (jail == null || jail.getModeSnapshot() == JailContainmentMode.PHYSICAL) {
                return; // not jailed, or breakable-walls mode
            }
            ResourceLocation posDim = player.level().dimension().location();
            if (JailRegion.contains(jail.getJailAnchor(), jail.getJailRadius(), jail.getJailDim(),
                    event.getPos(), posDim)) {
                event.setCanceled(true); // can't mine out of a CONTAINMENT/REINFORCED jail
            }
        });
    }
}
