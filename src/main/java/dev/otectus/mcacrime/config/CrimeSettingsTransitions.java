package dev.otectus.mcacrime.config;

import dev.otectus.mcacrime.McaCrime;
import dev.otectus.mcacrime.report.PlayerCrimeReportService;
import net.minecraft.server.MinecraftServer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * One coherent post-command/reload snapshot per tick; no halfway-imported policy is observable.
 *
 * <p>{@code ServerTickEvent.Pre} is the START phase of the Forge-era phased tick event.
 */
@EventBusSubscriber(modid = McaCrime.MOD_ID)
public final class CrimeSettingsTransitions {
    private static final Map<MinecraftServer, CrimeWorldSettings> LAST = new WeakHashMap<>();
    private CrimeSettingsTransitions() {}
    @SubscribeEvent public static void tick(ServerTickEvent.Pre event) {
        var current = CrimeWorldSettings.resolve(event.getServer());
        var previous = LAST.put(event.getServer(), current);
        if ((!current.playerReports() || !current.observations())
                && (previous == null || previous.playerReports() && previous.observations())) PlayerCrimeReportService.clearOffers();
    }
    @SubscribeEvent public static void stopped(ServerStoppedEvent event) { LAST.remove(event.getServer()); }
}
