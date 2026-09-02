package dev.otectus.mcacrime.client.hud;

import com.mojang.blaze3d.systems.RenderSystem;
import dev.otectus.mcacrime.McaCrime;
import dev.otectus.mcacrime.McaCrimeConfig;
import dev.otectus.mcacrime.client.ClientActionData;
import dev.otectus.mcacrime.client.ClientCaptiveData;
import dev.otectus.mcacrime.client.ClientSelfData;
import dev.otectus.mcacrime.client.NameColors;
import dev.otectus.mcacrime.client.screen.CrimeSprites;
import dev.otectus.mcacrime.util.TickFormat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterGuiOverlaysEvent;
import net.minecraftforge.client.gui.overlay.VanillaGuiOverlay;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * The mod's heads-up display: an action channel bar, a legal-status indicator, and a custody
 * countdown.
 *
 * <p>Before this, every piece of information the mod produced arrived as chat text, and the two most
 * time-critical pieces — how far an action had progressed and why it stopped — arrived as action-bar
 * spam that overwrote itself and then simply ceased. Registered through Forge's overlay event, so
 * there is no mixin here and none is needed.
 *
 * <p>Everything drawn is read from a client cache the server populated. Nothing here computes state.
 *
 * <p>The HUD is textured like vanilla's HUD, not like vanilla's containers: a translucent dark plate,
 * as the boss bar and the subtitle overlay use. The mod's screens went grey to match the inventory,
 * but a light grey slab floating over the world would match nothing — least of all the game.
 */
@Mod.EventBusSubscriber(modid = McaCrime.MOD_ID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class CrimeHudOverlays {

    private static final int BAR_W = 122;
    private static final int PAD = 4;

    private CrimeHudOverlays() {
    }

    @SubscribeEvent
    public static void register(RegisterGuiOverlaysEvent event) {
        // Above the hotbar layer so the channel bar is never hidden behind it.
        event.registerAbove(VanillaGuiOverlay.HOTBAR.id(), "crime_channel",
                (gui, graphics, partialTick, width, height) -> renderChannel(graphics, width, height));
        event.registerAbove(VanillaGuiOverlay.HOTBAR.id(), "crime_status",
                (gui, graphics, partialTick, width, height) -> renderStatus(graphics, width, height));
        event.registerAbove(VanillaGuiOverlay.HOTBAR.id(), "crime_custody",
                (gui, graphics, partialTick, width, height) -> renderCustody(graphics, width, height));
    }

    /** True when the HUD should stay out of the way entirely. */
    private static boolean suppressed() {
        Minecraft mc = Minecraft.getInstance();
        return mc.options.hideGui
                || mc.player == null
                || mc.player.isSpectator()
                || !McaCrimeConfig.CLIENT.hudEnabled.get();
    }

    private static HudAnchor anchor() {
        return McaCrimeConfig.CLIENT.hudAnchor.get();
    }

    private static int offsetX() {
        return McaCrimeConfig.CLIENT.hudOffsetX.get();
    }

    private static int offsetY() {
        return McaCrimeConfig.CLIENT.hudOffsetY.get();
    }

    // ------------------------------------------------------------------ channel bar

    /**
     * The action channel bar, and — for a few seconds after — how the action ended.
     *
     * <p>The outcome half of this is the point. Nine distinct cancellation reasons existed in the code
     * and none of them ever reached a player; "you moved, and the action broke off" is the difference
     * between a rule and a mystery.
     */
    private static void renderChannel(GuiGraphics graphics, int width, int height) {
        if (suppressed() || !McaCrimeConfig.CLIENT.hudChannelBar.get()) return;
        boolean channelling = ClientActionData.channelling();
        boolean outcome = ClientActionData.hasOutcome();
        if (!channelling && !outcome) return;

        Minecraft mc = Minecraft.getInstance();
        // Anchored above the hotbar rather than through the general anchor: this bar is about the
        // thing in your hands, and it belongs where the eye already is during an action.
        int x = (width - BAR_W) / 2;
        int y = height - 62;

        if (channelling) {
            Component label = Component.translatable(ClientActionData.labelKey());
            graphics.drawString(mc.font, label, x + (BAR_W - mc.font.width(label)) / 2, y - 10, 0xFFFFFF, true);
            CrimeSprites.bar(graphics, x, y, BAR_W, ClientActionData.fraction());
        } else {
            Component line = Component.translatable(ClientActionData.outcomeKey());

            // Three-byte colours on purpose. The fade composes its own alpha into the top byte below,
            // so giving these an explicit 0xFF would pin the text opaque and the outcome would vanish
            // in one frame instead of dimming out. Font only force-opaques an alpha below 4, which the
            // floor of 16 already prevents.
            int colour = ClientActionData.outcomeWasCancellation() ? 0xFFAA55 : 0x99DD99;
            int alpha = Math.max(16, Math.round(255 * ClientActionData.outcomeAlpha())) << 24;
            RenderSystem.enableBlend();
            graphics.drawString(mc.font, line, (width - mc.font.width(line)) / 2, y - 10,
                    alpha | colour, true);
            RenderSystem.disableBlend();
        }
    }

    // ------------------------------------------------------------------ status indicator

    /** Standing, Heat and Wanted at a glance, so the player is never guessing what the law thinks. */
    private static void renderStatus(GuiGraphics graphics, int width, int height) {
        if (suppressed() || !McaCrimeConfig.CLIENT.hudStatusIndicator.get()) return;
        // Nothing at all when there is nothing to say: a lawful player with no Heat should not have a
        // permanent badge telling them so.
        boolean interesting = ClientSelfData.heat() > 0 || ClientSelfData.wanted() || ClientSelfData.legalTarget();
        if (!interesting) return;

        Minecraft mc = Minecraft.getInstance();
        Component heat = Component.translatable("gui.mcacrime.hud.heat", ClientSelfData.heat());
        Component badge = ClientSelfData.legalTarget()
                ? Component.translatable("gui.mcacrime.hud.legal_target")
                : ClientSelfData.wanted() ? Component.translatable("gui.mcacrime.hud.wanted") : null;

        int lineWidth = mc.font.width(heat);
        if (badge != null) lineWidth = Math.max(lineWidth, mc.font.width(badge));
        int boxW = lineWidth + PAD * 2;
        int boxH = (badge == null ? 10 : 20) + PAD * 2;

        int x = anchor().x(width, boxW, offsetX());
        int y = anchor().y(height, boxH, offsetY());

        CrimeSprites.hudPlate(graphics, x, y, boxW, boxH);
        // The band line stays a fill: it is one pixel tall and carries a colour, so a sprite for it
        // would be the same pixels plus a texture bind.
        CrimeSprites.rule(graphics, x, y, boxW,
                0xFF000000 | (NameColors.rgb(ClientSelfData.band()) & 0xFFFFFF));
        graphics.drawString(mc.font, heat, x + PAD, y + PAD, 0xFFCCCCCC, false);
        if (badge != null) {
            graphics.drawString(mc.font, badge, x + PAD, y + PAD + 10, NameColors.RED_RGB, false);
        }
    }

    // ------------------------------------------------------------------ custody countdown

    /** The jail sentence or captivity clock, which previously existed only on the inventory card. */
    private static void renderCustody(GuiGraphics graphics, int width, int height) {
        if (suppressed() || !McaCrimeConfig.CLIENT.hudCustodyIndicator.get()) return;

        long jailTicks = ClientSelfData.jailRemainingTicks();
        boolean captive = ClientCaptiveData.captive();
        if (jailTicks <= 0 && !captive) return;

        Minecraft mc = Minecraft.getInstance();
        Component line;
        if (jailTicks > 0) {
            line = Component.translatable("gui.mcacrime.hud.jail", TickFormat.compact(jailTicks));
        } else if (ClientCaptiveData.capRemainingTicks() > 0) {
            line = Component.translatable("gui.mcacrime.hud.captive_timed",
                    TickFormat.clock(ClientCaptiveData.capRemainingTicks()));
        } else {
            line = Component.translatable("gui.mcacrime.hud.captive");
        }

        int boxW = mc.font.width(line) + PAD * 2;
        int boxH = 10 + PAD * 2;
        // Stacked one box further inward from the status box, so they never overlap. The anchor
        // already counts a bottom offset upward, so this needs no sign of its own.
        int stack = McaCrimeConfig.CLIENT.hudStatusIndicator.get() ? 30 : 0;
        int x = anchor().x(width, boxW, offsetX());
        int y = anchor().y(height, boxH, offsetY() + stack);

        CrimeSprites.hudPlate(graphics, x, y, boxW, boxH);
        graphics.drawString(mc.font, line, x + PAD, y + PAD, 0xFFFFB060, false);
    }
}
