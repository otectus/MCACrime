package dev.otectus.mcacrime.client;

import dev.otectus.mcacrime.McaCrime;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;

/**
 * Client-only setup: cache hygiene across connections.
 *
 * <p>The config screen factory moved to {@link McaCrimeClient}, where the mod container is injected.
 */
@EventBusSubscriber(modid = McaCrime.MOD_ID, value = Dist.CLIENT)
public final class CrimeClientSetup {

    private CrimeClientSetup() {
    }

    /**
     * Clears every client cache on disconnect.
     *
     * <p>Without this, joining a second world shows the first world's Heat, band colours, captivity
     * countdown and case file until the server happens to overwrite each one — and anything the new
     * server never sends (because the player is clean there) is never overwritten at all.
     */
    @SubscribeEvent
    public static void onLoggedOut(ClientPlayerNetworkEvent.LoggingOut event) {
        ClientSelfData.clear();
        ClientBandData.clear();
        ClientCaptiveData.clear();
        ClientActionData.clear();
        ClientChallengeData.clear();
        ClientCaseData.clear();
        ClientRestraintData.clear();
    }
}
