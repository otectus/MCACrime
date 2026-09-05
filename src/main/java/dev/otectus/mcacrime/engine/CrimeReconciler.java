package dev.otectus.mcacrime.engine;

import dev.otectus.mcacrime.McaCrime;
import dev.otectus.mcacrime.captivity.CustodyService;
import dev.otectus.mcacrime.event.CrimeBandSync;
import dev.otectus.mcacrime.jail.JailService;
import dev.otectus.mcacrime.network.CrimeNetwork;
import dev.otectus.mcacrime.state.world.CrimeWorldData;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;

/**
 * Login reconciliation (spec §2 "on login the player capability is reconciled against SavedData"). In
 * 0.1.0 there is nothing authoritative for the capability to pull back from {@code mcacrime.dat} (the
 * ledger is a later phase), so this is deliberately minimal: it ensures the world store exists, brings
 * the cached band / wanted flag in line with the <em>current</em> config (thresholds may have changed
 * between sessions), and pushes the initial display sync. It is also the documented seam where later
 * phases restore ledger-driven consequences on login.
 */
@EventBusSubscriber(modid = McaCrime.MOD_ID)
public final class CrimeReconciler {

    private CrimeReconciler() {
    }

    @SubscribeEvent
    public static void onPlayerLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            onLogin(player);
        }
    }

    public static void onLogin(ServerPlayer player) {
        MinecraftServer server = player.getServer();
        if (server != null) {
            // Touch (and dirty) the store so mcacrime.dat actually serialises even while empty.
            CrimeWorldData.get(server).setDirty();
        }
        CrimeState.recomputeDerived(player);
        JailService.reconcileOnLogin(player); // free a player whose jail became unusable (§7.4 no softlock)
        CustodyService.reconcileOnLogin(player); // free a kidnapping captive whose captor is gone (§8.4)
        // Finish an arrest whose escort was interrupted by the logout, a restart, or a death. The walk
        // is not replayed -- the sentence is what matters, and nobody was there to watch the walk.
        dev.otectus.mcacrime.enforcement.ArrestService.reconcileOnLogin(player);
        // Third removal path for the movement penalty, after the transition chokepoint and the respawn
        // handler: a crash between applying it and saving would otherwise leave a permanently slow
        // player with no arrest to explain it.
        if (!dev.otectus.mcacrime.enforcement.ArrestStates.isRestrained(player)) {
            dev.otectus.mcacrime.enforcement.RestraintHandlers.onReleased(player);
        } else {
            dev.otectus.mcacrime.enforcement.RestraintHandlers.onRestrained(player);
        }
        dev.otectus.mcacrime.enforcement.RestraintSync.syncOnLogin(player);
        dev.otectus.mcacrime.enforcement.RestraintSync.broadcast(player);
        CrimeNetwork.sendSelfStatus(player);
        // The weapon gate the Crime button greys itself out on is the server's, not the client's own
        // config file, so the client is told it rather than left to guess.
        CrimeNetwork.sendWeaponPolicy(player);
        CrimeNetwork.sendCaptiveStatus(player); // restore the captive screen/indicator on re-login
        CrimeBandSync.syncOnLogin(player);
    }
}
