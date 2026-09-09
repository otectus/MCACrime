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
import net.neoforged.api.distmarker.Dist;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.gui.VanillaGuiLayers;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;

/**
 * The mod's heads-up display: an action channel bar, a legal-status indicator, and a custody
 * countdown.
 *
 * <p>Before this, every piece of information the mod produced arrived as chat text, and the two most
 * time-critical pieces — how far an action had progressed and why it stopped — arrived as action-bar
 * spam that overwrote itself and then simply ceased. Registered as GUI layers above the hotbar, so
 * there is no mixin here and none is needed.
 *
 * <p>Everything drawn is read from a client cache the server populated. Nothing here computes state.
 *
 * <p>The HUD is textured like vanilla's HUD, not like vanilla's containers: a translucent dark plate,
 * as the boss bar and the subtitle overlay use. The mod's screens went grey to match the inventory,
 * but a light grey slab floating over the world would match nothing — least of all the game.
 */
@EventBusSubscriber(modid = McaCrime.MOD_ID, value = Dist.CLIENT)
public final class CrimeHudOverlays {

    private static final int BAR_W = 122;
    /** The one channel label that is somebody else's action against this player. */
    private static final String NPC_MUG_LABEL = "gui.mcacrime.action.npc_mug";
    private static final int PAD = 3;

    private CrimeHudOverlays() {
    }

    @SubscribeEvent
    public static void register(RegisterGuiLayersEvent event) {
        // Above the hotbar layer so the channel bar is never hidden behind it. Layer IDs are full
        // ResourceLocations now; the paths are the names the Forge overlays carried.
        event.registerAbove(VanillaGuiLayers.HOTBAR, McaCrime.id("crime_channel"),
                (graphics, deltaTracker) -> renderChannel(graphics, graphics.guiWidth(), graphics.guiHeight()));
        event.registerAbove(VanillaGuiLayers.HOTBAR, McaCrime.id("crime_status"),
                (graphics, deltaTracker) -> renderStatus(graphics, graphics.guiWidth(), graphics.guiHeight()));
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
            boolean mugged = NPC_MUG_LABEL.equals(ClientActionData.labelKey());
            if (mugged && !McaCrimeConfig.CLIENT.showNpcMuggingHud.get()) return;
            Component label = Component.translatable(ClientActionData.labelKey());
            graphics.drawString(mc.font, label, x + (BAR_W - mc.font.width(label)) / 2, y - 10, 0xFFFFFF, true);
            CrimeSprites.bar(graphics, x, y, BAR_W, ClientActionData.fraction());
            if (mugged) {
                // The counterplay, under the bar that is counting down on it. A player being mugged by
                // an NPC has no menu open and no reason to know that a weapon is the answer.
                Component hint = Component.translatable("gui.mcacrime.action.npc_mug.hint");
                graphics.drawString(mc.font, hint, x + (BAR_W - mc.font.width(hint)) / 2,
                        y + CrimeSprites.BAR_H + 2, 0xFFD98A, true);
            }
        } else {
            Component line = ClientActionData.outcomeText();

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
        if (suppressed() || Minecraft.getInstance().screen instanceof net.minecraft.client.gui.screens.ChatScreen) return;
        // Nothing at all when there is nothing to say: a lawful player with no Heat should not have a
        // permanent badge telling them so.
        boolean interesting = ClientSelfData.heat() > 0 || ClientSelfData.wanted() || ClientSelfData.legalTarget();
        boolean showHeat = interesting && McaCrimeConfig.CLIENT.hudStatusIndicator.get();
        Component custody = McaCrimeConfig.CLIENT.hudCustodyIndicator.get() ? custodyLine() : null;
        if (!showHeat && custody == null) return;

        Minecraft mc = Minecraft.getInstance();
        Component heat = Component.translatable("gui.mcacrime.hud.heat", ClientSelfData.heat());
        Component badge = ClientSelfData.legalTarget()
                ? Component.translatable("gui.mcacrime.hud.legal_target")
                : ClientSelfData.wanted() ? Component.translatable("gui.mcacrime.hud.wanted") : null;
        if (badge != null) heat = heat.copy().append("  ").append(badge.copy().withStyle(
                style -> style.withColor(NameColors.RED_RGB)));

        int lineWidth = showHeat ? mc.font.width(heat) : 0;
        if (custody != null) lineWidth = Math.max(lineWidth, mc.font.width(custody));
        int boxW = lineWidth + PAD * 2;
        int boxH = (showHeat && custody != null ? 20 : 10) + PAD * 2;

        CrimeHudLayout.Placement place = CrimeHudLayout.place(anchor(), width, height, boxW, boxH,
                offsetX(), offsetY());
        graphics.pose().pushPose();
        graphics.pose().translate(place.x(), place.y(), 0);
        graphics.pose().scale(place.scale(), place.scale(), 1F);
        int x = 0;
        int y = 0;

        CrimeSprites.hudPlate(graphics, x, y, boxW, boxH);
        // The band line stays a fill: it is one pixel tall and carries a colour, so a sprite for it
        // would be the same pixels plus a texture bind.
        CrimeSprites.rule(graphics, x, y, boxW,
                0xFF000000 | (NameColors.rgb(ClientSelfData.band()) & 0xFFFFFF));
        if (showHeat) graphics.drawString(mc.font, heat, PAD, PAD, 0xFFCCCCCC, false);
        if (custody != null) graphics.drawString(mc.font, custody, PAD, PAD + (showHeat ? 10 : 0), 0xFFFFB060, false);
        graphics.pose().popPose();
    }

    // ------------------------------------------------------------------ custody countdown

    /** The jail sentence or captivity clock, which previously existed only on the inventory card. */
    private static Component custodyLine() {
        long jailTicks = ClientSelfData.jailRemainingTicks();
        boolean captive = ClientCaptiveData.captive();
        if (jailTicks <= 0 && !captive) return null;

        Component line;
        if (jailTicks > 0) {
            line = Component.translatable("gui.mcacrime.hud.jail", TickFormat.compact(jailTicks));
        } else if (ClientCaptiveData.capRemainingTicks() > 0) {
            line = Component.translatable("gui.mcacrime.hud.captive_timed",
                    TickFormat.clock(ClientCaptiveData.capRemainingTicks()));
        } else {
            line = Component.translatable("gui.mcacrime.hud.captive");
        }

        return line;
    }
}
