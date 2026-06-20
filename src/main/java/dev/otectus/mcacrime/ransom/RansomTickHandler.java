package dev.otectus.mcacrime.ransom;

import dev.otectus.mcacrime.McaCrime;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.server.ServerLifecycleHooks;

/**
 * Re-validates open ransom demands ~once per second (spec §8.5): expiring stale ones and failing any whose
 * victim died / escaped / was rescued / was jailed. Cheap — it iterates only the small active-demand list.
 */
@Mod.EventBusSubscriber(modid = McaCrime.MOD_ID)
public final class RansomTickHandler {

    private static int counter;

    private RansomTickHandler() {
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        if (++counter < 20) {
            return; // ~1/s
        }
        counter = 0;
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server != null) {
            RansomService.validateOpenDemands(server);
        }
    }
}
