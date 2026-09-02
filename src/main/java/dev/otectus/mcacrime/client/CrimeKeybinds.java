package dev.otectus.mcacrime.client;

import com.mojang.blaze3d.platform.InputConstants;
import dev.otectus.mcacrime.McaCrime;
import dev.otectus.mcacrime.McaCrimeConfig;
import dev.otectus.mcacrime.action.ActionMenuKind;
import dev.otectus.mcacrime.client.screen.CaseDossierScreen;
import dev.otectus.mcacrime.client.screen.GuardChallengeScreen;
import dev.otectus.mcacrime.network.CrimeNetwork;
import dev.otectus.mcacrime.network.RequestSelfMenuC2SPacket;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.client.settings.KeyConflictContext;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import org.lwjgl.glfw.GLFW;

/**
 * The mod's key bindings.
 *
 * <p>Everything this mod does to a player was previously reachable only through chat commands or by
 * walking up to a villager. That is fine for acting on the world and wrong for reading your own
 * situation: a player who wants to know what they are wanted for should not have to type, and a
 * captive should not have to remember a command while a countdown runs.
 *
 * <p>Defaults are chosen from keys vanilla leaves free ({@code J} and {@code K}), so a fresh install
 * conflicts with nothing. The third binding ships unbound on purpose — it is a preference, not a need,
 * and claiming a third key for it would be presumptuous.
 */
@Mod.EventBusSubscriber(modid = McaCrime.MOD_ID, value = Dist.CLIENT)
public final class CrimeKeybinds {

    private static final String CATEGORY = "key.categories.mcacrime";

    /** Opens the player's own case file. */
    public static final KeyMapping OPEN_DOSSIER = new KeyMapping("key.mcacrime.dossier",
            KeyConflictContext.IN_GAME, InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_K, CATEGORY);

    /**
     * Opens the panel about your own situation — the captive panel while held, the standing panel
     * otherwise. One key rather than two, because which one applies is never ambiguous.
     */
    public static final KeyMapping OPEN_SELF_PANEL = new KeyMapping("key.mcacrime.self_panel",
            KeyConflictContext.IN_GAME, InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_J, CATEGORY);

    /** Reopens the guard challenge if it was dismissed while the window is still running. */
    public static final KeyMapping REOPEN_CHALLENGE = new KeyMapping("key.mcacrime.challenge",
            KeyConflictContext.IN_GAME, InputConstants.Type.KEYSYM, InputConstants.UNKNOWN.getValue(), CATEGORY);

    private CrimeKeybinds() {
    }

    @Mod.EventBusSubscriber(modid = McaCrime.MOD_ID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.MOD)
    public static final class Registration {
        private Registration() {
        }

        @SubscribeEvent
        public static void onRegisterKeyMappings(RegisterKeyMappingsEvent event) {
            event.register(OPEN_DOSSIER);
            event.register(OPEN_SELF_PANEL);
            event.register(REOPEN_CHALLENGE);
        }
    }

    /**
     * Drains the click queues once per client tick, and runs the challenge countdown.
     *
     * <p>{@code consumeClick} is drained in a loop rather than checked once, so a key pressed several
     * times in one tick does not queue up several screens. Presses are ignored entirely while a screen
     * is already open — otherwise the same key that opens the dossier closes and reopens it.
     */
    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        // Both local clocks are held while the game is paused. ClientTickEvent keeps firing in a paused
        // single-player world but the integrated server is frozen, so a client that ran its countdown
        // through the pause menu would drift ahead of the sentence it is displaying.
        if (!minecraft.isPaused()) {
            ClientChallengeData.tick();
            ClientSelfData.tick();
        }

        boolean busy = minecraft.screen != null || minecraft.player == null;
        boolean dossier = drain(OPEN_DOSSIER);
        boolean selfPanel = drain(OPEN_SELF_PANEL);
        boolean challenge = drain(REOPEN_CHALLENGE);
        if (busy) {
            return;
        }

        if (dossier) {
            minecraft.setScreen(new CaseDossierScreen(null));
        } else if (selfPanel) {
            // Which panel this is depends on whether you are being held, and the server decides that.
            // Asking for CAPTIVE while free would produce an empty panel, so the client picks the kind
            // from what it was last told and the server still validates every row either way.
            boolean captive = ClientCaptiveData.captive()
                    && McaCrimeConfig.CLIENT.captiveScreenToggle.get();
            CrimeNetwork.CHANNEL.sendToServer(new RequestSelfMenuC2SPacket(
                    captive ? ActionMenuKind.CAPTIVE : ActionMenuKind.SELF));
        } else if (challenge && ClientChallengeData.active()) {
            minecraft.setScreen(new GuardChallengeScreen());
        }
    }

    /** True when the mapping was pressed at least once since the last tick; drains the whole queue. */
    private static boolean drain(KeyMapping mapping) {
        boolean pressed = false;
        while (mapping.consumeClick()) {
            pressed = true;
        }
        return pressed;
    }
}
