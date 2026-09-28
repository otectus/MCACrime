package dev.otectus.mcacrime.client;

import dev.otectus.mcacrime.McaCrime;
import dev.otectus.mcacrime.restraint.PhysicalRestraintView;
import dev.otectus.mcacrime.restraint.RestraintDefinitions;
import dev.otectus.mcacrime.restraint.RestraintSlot;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.player.LocalPlayer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterGuiOverlaysEvent;
import net.minecraftforge.client.gui.overlay.VanillaGuiOverlay;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.UUID;

/**
 * The first-person view from inside a hood (0.7.5 M2.10).
 *
 * <p>Drawn for the <b>local player only</b>, and that is the invariant the test asserts: a hooded
 * player sees the inside of a sack, and everybody else sees the sack on their head via the worn
 * model. An overlay that used somebody else's restraint state would black out the wrong screen, and
 * an overlay drawn for observers would blind a whole room because one person was hooded.
 *
 * <p>An overlay rather than a fog or a blindness effect. Fog is a world property and would be visible
 * to anybody sharing the render pass; a {@code MobEffect} would show in the inventory and be curable
 * with milk, which would make the hood a suggestion — the same reason the movement penalty is an
 * attribute modifier rather than a potion.
 *
 * <p>Presentation only, and the direction matters: this obscures a view, it does not create one. A
 * client that never drew this is restricted exactly as much as one that did, because the restriction
 * lives on the server. The converse — a client toggle lifting a server restriction — is not possible
 * here because this class decides nothing.
 */
@Mod.EventBusSubscriber(modid = McaCrime.MOD_ID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class HoodOverlayHandler {

    /** How much of the screen edge stays dark. The gap in the weave is what the wearer sees through. */
    private static final int OPAQUE = 0xF0100A06;
    private static final float GAP_WIDTH = 0.34F;
    private static final float GAP_HEIGHT = 0.22F;

    private HoodOverlayHandler() {
    }

    @SubscribeEvent
    public static void register(RegisterGuiOverlaysEvent event) {
        // Under the hotbar layer: the hood obscures the world, not the player's own inventory, and a
        // player who cannot see their hotbar cannot be told which key frees them.
        event.registerBelow(VanillaGuiOverlay.HOTBAR.id(), "crime_hood",
                (gui, graphics, partialTick, width, height) -> render(graphics, width, height));
    }

    /**
     * Whether {@code subject} is drawn a hood overlay.
     *
     * <p>Pure and public so the ownership rule can be asserted directly: the answer depends on the
     * subject's own slots and on nobody else's.
     */
    public static boolean hooded(UUID subject) {
        PhysicalRestraintView view = ClientPhysicalRestraintData.get(subject).orElse(null);
        if (view == null) {
            return false;
        }
        return view.slot(RestraintSlot.HEAD)
                .flatMap(slotView -> RestraintDefinitions.get(slotView.definitionId()))
                .map(definition -> definition.render().firstPersonOverlay())
                .orElse(false);
    }

    private static void render(GuiGraphics graphics, int width, int height) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || mc.options.hideGui || player.isSpectator()) {
            return;
        }
        // The local player's own uuid, taken from the player object rather than from a cached id: a
        // client that respawned or changed dimension keeps the same uuid, and reading it here means
        // there is no second copy of "who am I" to go stale.
        if (!hooded(player.getUUID())) {
            return;
        }
        // Third person sees the worn hood on the body; blacking out the screen as well would leave a
        // player unable to tell whether they are hooded or the game has stopped drawing.
        if (!mc.options.getCameraType().isFirstPerson()) {
            return;
        }
        int gapW = Math.round(width * GAP_WIDTH);
        int gapH = Math.round(height * GAP_HEIGHT);
        int left = (width - gapW) / 2;
        int top = (height - gapH) / 2;
        graphics.fill(0, 0, width, top, OPAQUE);
        graphics.fill(0, top + gapH, width, height, OPAQUE);
        graphics.fill(0, top, left, top + gapH, OPAQUE);
        graphics.fill(left + gapW, top, width, top + gapH, OPAQUE);
    }
}
