package dev.otectus.mcacrime.engine;

import dev.otectus.mcacrime.McaCrime;
import dev.otectus.mcacrime.event.CrimeBandSync;
import dev.otectus.mcacrime.jail.JailService;
import dev.otectus.mcacrime.network.CrimeNetwork;
import dev.otectus.mcacrime.state.world.CrimeWorldData;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Login reconciliation (spec §2 "on login the player capability is reconciled against SavedData"). In
 * 0.1.0 there is nothing authoritative for the capability to pull back from {@code mcacrime.dat} (the
 * ledger is a later phase), so this is deliberately minimal: it ensures the world store exists, brings
 * the cached band / wanted flag in line with the <em>current</em> config (thresholds may have changed
 * between sessions), and pushes the initial display sync. It is also the documented seam where later
 * phases restore ledger-driven consequences on login.
 */
@Mod.EventBusSubscriber(modid = McaCrime.MOD_ID)
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
        CrimeNetwork.sendSelfStatus(player);
        CrimeBandSync.syncOnLogin(player);
    }
}
