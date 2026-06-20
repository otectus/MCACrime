package dev.otectus.mcacrime.event;

import dev.otectus.mcacrime.McaCrime;
import dev.otectus.mcacrime.McaCrimeConfig;
import dev.otectus.mcacrime.crime.Band;
import dev.otectus.mcacrime.engine.CrimeState;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.ServerChatEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import javax.annotation.Nullable;
import java.util.Locale;

/**
 * Server-side band coloring in chat (spec §10.3/§12), wiring the previously-reserved {@code chatFormatToggle}.
 * The authoritative switch is the COMMON {@code chatNameColorEnabled}; the client toggle is a display hint.
 *
 * <p>Non-destructive by construction: GREY is left untouched, and the band is shown as a colored <em>prefix
 * marker</em> on the message rather than by recoloring the sender name. (1.20.1 signed chat surfaces only
 * the message body to {@link ServerChatEvent}, not the decorated name, so a prefix marker is the robust,
 * conflict-free equivalent of the §10.3 "color names in chat by band" intent and never fights the
 * client-side nameplate coloring.) FULL shows the band label tag; PREFIX_ONLY shows a small colored dot.
 */
@Mod.EventBusSubscriber(modid = McaCrime.MOD_ID)
public final class ChatNameColor {

    private ChatNameColor() {
    }

    @SubscribeEvent
    public static void onServerChat(ServerChatEvent event) {
        if (!McaCrimeConfig.COMMON.chatNameColorEnabled.get()) {
            return;
        }
        ServerPlayer player = event.getPlayer();
        if (player == null) {
            return;
        }
        Band band = CrimeState.getBand(player);
        Integer rgb = bandRgb(band);
        if (rgb == null) {
            return; // GREY: leave chat untouched
        }
        Style style = Style.EMPTY.withColor(TextColor.fromRgb(rgb));
        MutableComponent marker;
        if (McaCrimeConfig.COMMON.chatNameColorMode.get() == McaCrimeConfig.NameColorMode.FULL) {
            marker = Component.literal("[")
                    .append(Component.translatable("mcacrime.band." + band.name().toLowerCase(Locale.ROOT)))
                    .append("] ")
                    .setStyle(style);
        } else {
            marker = Component.literal("● ").setStyle(style); // ●
        }
        event.setMessage(Component.empty().append(marker).append(event.getMessage()));
    }

    /** The band's chat RGB, or null for GREY (left un-colored, non-destructive). Server-side mirror of the card colors. */
    @Nullable
    private static Integer bandRgb(Band band) {
        return switch (band) {
            case BLUE -> 0x5C9DFF;
            case RED -> 0xFF5555;
            case GREY -> null;
        };
    }
}
