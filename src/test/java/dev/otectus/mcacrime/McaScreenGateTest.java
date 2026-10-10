package dev.otectus.mcacrime;

import dev.otectus.mcacrime.compat.mca.client.McaInteractionScreenBridge;
import org.junit.jupiter.api.Test;

import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Crime button's screen hook may run on any screen at any point of the lifecycle (0.7.7).
 *
 * <p>A launch whose mod loading failed opens the loader's error screen before the client config is
 * loaded, and reading a config value then throws. The hook read the config first, so the error screen
 * crashed with "Cannot get config value before config is loaded" and the loader's own report -- the one
 * explaining what actually failed -- was never shown. A supplier that throws stands in for an unloaded
 * config here: these screens must never touch it.
 */
class McaScreenGateTest {

    private static final String MCA_SCREEN = "net.conczin.mca.client.gui.InteractScreen";
    private static final String FORGE_MCA_SCREEN = "forge.net.mca.client.gui.InteractScreen";

    private static final BooleanSupplier UNLOADED = () -> {
        throw new IllegalStateException("Cannot get config value before config is loaded.");
    };

    @Test
    void anotherScreenNeverReadsTheConfigAtAll() {
        for (String screen : new String[]{
                "net.neoforged.neoforge.client.gui.LoadingErrorScreen",
                "net.minecraftforge.client.gui.LoadingErrorScreen",
                "net.minecraft.client.gui.screens.TitleScreen",
                "net.minecraft.client.gui.screens.inventory.InventoryScreen"}) {
            assertFalse(McaInteractionScreenBridge.shouldAttach(screen, UNLOADED, UNLOADED), screen);
        }
    }

    @Test
    void anUnloadedConfigIsAskedAboutButNeverRead() {
        assertFalse(McaInteractionScreenBridge.shouldAttach(MCA_SCREEN, () -> false, UNLOADED));
    }

    @Test
    void mcasScreenFollowsTheSettingOnceLoaded() {
        assertTrue(McaInteractionScreenBridge.shouldAttach(MCA_SCREEN, () -> true, () -> true));
        assertTrue(McaInteractionScreenBridge.shouldAttach(FORGE_MCA_SCREEN, () -> true, () -> true),
                "every MCA package root");
        assertFalse(McaInteractionScreenBridge.shouldAttach(MCA_SCREEN, () -> true, () -> false), "button off");
    }

    @Test
    void aRefusalIsNeverRemembered() {
        assertFalse(McaInteractionScreenBridge.shouldAttach(MCA_SCREEN, () -> false, () -> true));
        assertTrue(McaInteractionScreenBridge.shouldAttach(MCA_SCREEN, () -> true, () -> true),
                "the first MCA screen after the config loads is decided afresh");
    }
}
