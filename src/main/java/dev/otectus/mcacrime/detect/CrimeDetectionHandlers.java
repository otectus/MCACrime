package dev.otectus.mcacrime.detect;

import dev.otectus.mcacrime.McaCrime;
import dev.otectus.mcacrime.McaCrimeConfig;
import net.minecraft.server.level.ServerLevel;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * The Forge-bus entry points for crime detection (spec §5.3). Server-side only (guarded by the
 * {@link ServerLevel} check); the master toggle short-circuits before any work. All real logic lives in
 * {@link CrimeDetector} / {@link CrimeGate}.
 */
@Mod.EventBusSubscriber(modid = McaCrime.MOD_ID)
public final class CrimeDetectionHandlers {

    private CrimeDetectionHandlers() {
    }

    @SubscribeEvent
    public static void onLivingHurt(LivingHurtEvent event) {
        if (!McaCrimeConfig.COMMON.enableCrimeDetection.get()) {
            return;
        }
        if (event.getEntity().level() instanceof ServerLevel level) {
            CrimeDetector.onHarm(event.getEntity(), event.getSource(), event.getAmount(), level);
        }
    }

    @SubscribeEvent
    public static void onLivingDeath(LivingDeathEvent event) {
        if (!McaCrimeConfig.COMMON.enableCrimeDetection.get()) {
            return;
        }
        if (event.getEntity().level() instanceof ServerLevel level) {
            CrimeDetector.onKill(event.getEntity(), event.getSource(), level);
        }
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        CrimeDetector.clearAttacker(event.getEntity().getUUID());
    }
}
