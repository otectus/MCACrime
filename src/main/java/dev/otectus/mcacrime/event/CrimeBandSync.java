package dev.otectus.mcacrime.event;

import dev.otectus.mcacrime.McaCrime;
import dev.otectus.mcacrime.api.event.KarmaChangedEvent;
import dev.otectus.mcacrime.crime.Band;
import dev.otectus.mcacrime.engine.CrimeState;
import dev.otectus.mcacrime.network.CrimeNetwork;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Keeps every client's view of player bands current for nameplate coloring (spec §4.1, §10.3). Bands
 * are broadcast only on the rare band <em>transition</em> (not per karma point), and a full snapshot is
 * pushed to each joining client by {@link #syncOnLogin(ServerPlayer)} (called from the login reconcile).
 */
@Mod.EventBusSubscriber(modid = McaCrime.MOD_ID)
public final class CrimeBandSync {

    private CrimeBandSync() {
    }

    @SubscribeEvent
    public static void onKarmaChanged(KarmaChangedEvent event) {
        if (event.isBandChanged()) {
            ServerPlayer player = event.getPlayer();
            CrimeNetwork.broadcastBand(player.getUUID(), event.getNewBand());
        }
    }

    /** Sends the joining player every online band, and announces the joiner's band to everyone. */
    public static void syncOnLogin(ServerPlayer joiner) {
        MinecraftServer server = joiner.getServer();
        if (server == null) {
            return;
        }
        Map<UUID, Band> bands = new HashMap<>();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            bands.put(player.getUUID(), CrimeState.getBand(player));
        }
        CrimeNetwork.sendBandBulk(joiner, bands);
        CrimeNetwork.broadcastBand(joiner.getUUID(), CrimeState.getBand(joiner));
    }
}
