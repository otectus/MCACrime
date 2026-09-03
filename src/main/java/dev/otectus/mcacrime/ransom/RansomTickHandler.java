package dev.otectus.mcacrime.ransom;

import dev.otectus.mcacrime.McaCrime;
import net.minecraft.server.MinecraftServer;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

/**
 * Re-validates open ransom demands ~once per second (spec §8.5): expiring stale ones and failing any whose
 * victim died / escaped / was rescued / was jailed. Cheap — it iterates only the small active-demand list.
 */
@EventBusSubscriber(modid = McaCrime.MOD_ID)
public final class RansomTickHandler {

    private static int counter;

    private RansomTickHandler() {
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
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
