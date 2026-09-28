package dev.otectus.mcacrime.config;

import dev.otectus.mcacrime.McaCrime;
import dev.otectus.mcacrime.report.PlayerCrimeReportService;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import java.util.Map;
import java.util.WeakHashMap;

/** One coherent post-command/reload snapshot per tick; no halfway-imported policy is observable. */
@Mod.EventBusSubscriber(modid = McaCrime.MOD_ID)
public final class CrimeSettingsTransitions {
    private static final Map<MinecraftServer, CrimeWorldSettings> LAST = new WeakHashMap<>();
    private CrimeSettingsTransitions() {}
    @SubscribeEvent public static void tick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.START) return;
        var current = CrimeWorldSettings.resolve(event.getServer());
        var previous = LAST.put(event.getServer(), current);
        if ((!current.playerReports() || !current.observations())
                && (previous == null || previous.playerReports() && previous.observations())) PlayerCrimeReportService.clearOffers();
    }
    @SubscribeEvent public static void stopped(ServerStoppedEvent event) { LAST.remove(event.getServer()); }
}
