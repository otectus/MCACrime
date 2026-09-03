package dev.otectus.mcacrime.client;

import dev.otectus.mcacrime.McaCrime;
import dev.otectus.mcacrime.client.network.CrimeClientPayloadHandler;
import dev.otectus.mcacrime.network.CrimeClientPayloadRouter;
import dev.otectus.mcacrime.client.screen.CrimeConfigScreen;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;

/**
 * The client-only owner. Anything that touches {@code Minecraft} or a screen is constructed from
 * here rather than from {@link McaCrime}, so the dedicated server never loads a client class.
 *
 * <p>Registering the config screen factory is what puts a Config button on this mod's row in the
 * Mods list. Without it the client options are only reachable by editing a TOML file, which for
 * options about nameplates and HUD placement is the wrong ask — those are exactly the settings
 * somebody wants to change while looking at the thing they affect.
 */
@Mod(value = McaCrime.MOD_ID, dist = Dist.CLIENT)
public final class McaCrimeClient {

    public McaCrimeClient(IEventBus modBus, ModContainer container) {
        container.registerExtensionPoint(
                IConfigScreenFactory.class,
                (ignored, parent) -> new CrimeConfigScreen(parent));
        CrimeClientPayloadRouter.install(new CrimeClientPayloadHandler());
    }
}
