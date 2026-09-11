package dev.otectus.mcacrime.mug.npc;

import dev.otectus.mcacrime.McaCrime;
import dev.otectus.mcacrime.McaCrimeConfig;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;

/**
 * The two moments a player is owed a mugging grace period for having been away (0.7.0).
 *
 * <p>Both are the same complaint from the player's side: being robbed in the first seconds of a
 * session, or the first seconds after a death, is the version of NPC mugging that reads as the mod
 * being broken rather than as a village having a thief in it. Neither grant shortens an existing one,
 * so a player who respawns inside a long post-mugging window keeps that window.
 *
 * <p>Release from jail and from custody are granted where those releases happen, because that is where
 * the release is known to have succeeded.
 */
@EventBusSubscriber(modid = McaCrime.MOD_ID)
public final class MugProtectionEvents {

    private MugProtectionEvents() {
    }

    @SubscribeEvent
    public static void onRespawn(PlayerEvent.PlayerRespawnEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            MugProtection.grant(player, McaCrimeConfig.COMMON.respawnMugProtectionTicks.get());
        }
    }

    @SubscribeEvent
    public static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            MugProtection.grant(player, McaCrimeConfig.COMMON.loginMugProtectionTicks.get());
        }
    }
}
