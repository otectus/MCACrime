package dev.otectus.mcacrime.client;

import dev.otectus.mcacrime.McaCrime;
import dev.otectus.mcacrime.McaCrimeConfig;
import dev.otectus.mcacrime.crime.Band;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.RenderNameTagEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Non-destructive nameplate coloring by band (spec §4.1, §10.3). Client + Forge bus only. GREY players
 * are left entirely untouched. In FULL mode the name is wrapped in a parent whose style sets the band
 * color, so only <em>un-styled</em> descendants inherit it — names already styled by nickname/format
 * mods keep their styling. PREFIX_ONLY adds a small colored marker and never alters the name itself.
 */
@Mod.EventBusSubscriber(modid = McaCrime.MOD_ID, value = Dist.CLIENT)
public final class CrimeNameRenderHandlers {

    private CrimeNameRenderHandlers() {
    }

    @SubscribeEvent
    public static void onRenderNameTag(RenderNameTagEvent event) {
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
    }
}
