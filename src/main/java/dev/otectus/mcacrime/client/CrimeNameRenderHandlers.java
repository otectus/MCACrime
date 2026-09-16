package dev.otectus.mcacrime.client;

import dev.otectus.mcacrime.McaCrime;
import dev.otectus.mcacrime.McaCrimeConfig;
import dev.otectus.mcacrime.crime.Band;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.RenderNameTagEvent;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.util.TriState;

/**
 * Non-destructive nameplate coloring by band (spec §4.1, §10.3). Client + Forge bus only. GREY players
 * are left entirely untouched. In FULL mode the name is wrapped in a parent whose style sets the band
 * color, so only <em>un-styled</em> descendants inherit it — names already styled by nickname/format
 * mods keep their styling. PREFIX_ONLY adds a small colored marker and never alters the name itself.
 */
@EventBusSubscriber(modid = McaCrime.MOD_ID, value = Dist.CLIENT)
public final class CrimeNameRenderHandlers {

    private CrimeNameRenderHandlers() {
    }

    /**
     * A masked player has no nameplate (0.7.0). Presentation only, and client-side only: the server
     * never stops sending the name, because a client that chose not to hide it must not end up in a
     * different world from one that did.
     *
     * <p>{@code RenderNameTagEvent} carries a render tri-state rather than being cancelable, so this
     * runs at HIGHEST and denies; the recolor below then has nothing left to colour and says so itself.
     */
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onMaskedNameTag(RenderNameTagEvent event) {
        if (!McaCrimeConfig.CLIENT.maskHidesNameTag.get()) {
            return;
        }
        if (event.getEntity() instanceof AbstractClientPlayer player
                && dev.otectus.mcacrime.mask.Masks.isMasked(player)) {
            event.setCanRender(TriState.FALSE);
        }
    }

    @SubscribeEvent
    public static void onRenderNameTag(RenderNameTagEvent event) {
        if (event.canRender() == TriState.FALSE) {
            return; // something (a mask, or another mod) has already said this name is not drawn
        }
        if (!McaCrimeConfig.CLIENT.nameColorEnabled.get()) {
            return;
        }
        if (!(event.getEntity() instanceof AbstractClientPlayer player)) {
            return; // 0.1.0 colors players only
        }
        Band band = ClientBandData.bandOf(player.getUUID());
        Integer rgb = NameColors.rgbOrNull(band);
        if (rgb == null) {
            return; // GREY / unknown: leave the vanilla name untouched
        }
        TextColor color = TextColor.fromRgb(rgb);
        Component content = event.getContent();
        if (McaCrimeConfig.CLIENT.nameColorMode.get() == McaCrimeConfig.NameColorMode.PREFIX_ONLY) {
            MutableComponent prefix = Component.literal("● ").setStyle(Style.EMPTY.withColor(color));
            event.setContent(prefix.append(content));
        } else {
            // Parent sets the color; children with their own color keep it (non-destructive).
            event.setContent(Component.empty().setStyle(Style.EMPTY.withColor(color)).append(content));
        }
    }

    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        ClientBandData.clear();
        ClientSelfData.clear();
        ClientCaptiveData.clear();
        ClientActionData.clear();
    }

    /**
     * Ages the action-outcome fade. This is the only client-side clock the mod owns: everything else
     * it draws is a value the server pushed, but "how long has this message been on screen" is
     * inherently local and would cost a packet per tick to ask about.
     */
    @SubscribeEvent
    public static void onClientTick(net.neoforged.neoforge.client.event.ClientTickEvent.Post event) {
        ClientActionData.tick();
    }
}
