package dev.otectus.mcacrime.restraint;

import dev.otectus.mcacrime.McaCrime;
import dev.otectus.mcacrime.McaCrimeConfig;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.ServerChatEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import javax.annotation.Nullable;

/**
 * {@code restraints.definitions.headTapeMufflesTextChat}: a gagged player's typed chat arrives muffled.
 *
 * <p>Off by default, and deliberately not part of any {@link RestrictionPolicy}: chat is a
 * {@link ProtectedAction}, which no restriction may cancel, and this does not cancel it either. The
 * message is still sent, still attributed and still signed off by the server; only the words become
 * "mmph". That keeps the moderation problem the default avoids -- a player who cannot say "let me
 * out" -- smaller when an operator opts in: everybody can still see that the player is talking.
 *
 * <p>"Gagged" is the resolved physical policy's {@code voiceGag}, which head tape imposes and a
 * restraint profile may add to another definition. The bundle hood does not gag.
 *
 * <p>{@code HIGH} priority, so {@code event/ChatNameColor}'s band marker is prefixed to the muffled
 * line rather than muffled with it.
 */
@Mod.EventBusSubscriber(modid = McaCrime.MOD_ID)
public final class RestraintChatMuffle {

    private RestraintChatMuffle() {
    }

    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onServerChat(ServerChatEvent event) {
        ServerPlayer player = event.getPlayer();
        if (player == null || !enabled() || !RestraintService.policy(player).voiceGag()) {
            return;
        }
        event.setMessage(Component.literal(muffle(event.getRawText())));
    }

    private static boolean enabled() {
        try {
            return McaCrimeConfig.COMMON.headTapeMufflesTextChat.get();
        } catch (IllegalStateException e) {
            return false;
        }
    }

    /**
     * What a gagged player's message sounds like: one "mmph" per word, longer for longer words, keeping
     * a capital and a closing {@code !} or {@code ?} so the tone survives the tape.
     *
     * <p>Pure, and never empty: a message with no words at all is still an attempt to speak.
     */
    public static String muffle(@Nullable String raw) {
        String text = raw == null ? "" : raw.trim();
        StringBuilder out = new StringBuilder();
        for (String word : text.split("\\s+")) {
            long letters = word.codePoints().filter(Character::isLetterOrDigit).count();
            if (letters == 0) {
                continue;
            }
            if (!out.isEmpty()) {
                out.append(' ');
            }
            out.append(Character.isUpperCase(word.codePointAt(0)) ? 'M' : 'm')
                    .append("m".repeat((int) Math.min(3L, Math.max(0L, letters - 2L))))
                    .append("ph");
        }
        if (out.isEmpty()) {
            return "Mmph.";
        }
        char last = text.charAt(text.length() - 1);
        if (last == '!' || last == '?') {
            out.append(last);
        }
        return out.toString();
    }
}
