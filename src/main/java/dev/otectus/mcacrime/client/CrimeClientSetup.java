package dev.otectus.mcacrime.client;

import dev.otectus.mcacrime.McaCrime;
import dev.otectus.mcacrime.client.screen.CrimeConfigScreen;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.ConfigScreenHandler;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;

/**
 * Client-only setup: the settings screen registration, and cache hygiene across connections.
 *
 * <p>Registering the config screen is what puts a Config button on this mod's row in the Mods list.
 * Without it the client options are only reachable by editing a TOML file, which for options about
 * nameplates and HUD placement is the wrong ask — those are exactly the settings somebody wants to
 * change while looking at the thing they affect.
 */
@Mod.EventBusSubscriber(modid = McaCrime.MOD_ID, value = Dist.CLIENT)
public final class CrimeClientSetup {

    private CrimeClientSetup() {
    }

    @Mod.EventBusSubscriber(modid = McaCrime.MOD_ID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.MOD)
    public static final class ModBus {
        private ModBus() {
        }

        @SubscribeEvent
        public static void onClientSetup(FMLClientSetupEvent event) {
            event.enqueueWork(() -> ModLoadingContext.get().registerExtensionPoint(
                    ConfigScreenHandler.ConfigScreenFactory.class,
                    () -> new ConfigScreenHandler.ConfigScreenFactory(
                            (minecraft, parent) -> new CrimeConfigScreen(parent))));
        }
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
        ClientWeaponPolicy.clear();
        ClientCriminalJobData.clear();
    }
}
