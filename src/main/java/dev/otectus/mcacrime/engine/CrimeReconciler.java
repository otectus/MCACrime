package dev.otectus.mcacrime.engine;

import dev.otectus.mcacrime.McaCrime;
import dev.otectus.mcacrime.captivity.CustodyService;
import dev.otectus.mcacrime.event.CrimeBandSync;
import dev.otectus.mcacrime.jail.JailService;
import dev.otectus.mcacrime.network.CrimeNetwork;
import dev.otectus.mcacrime.economy.Currencies;
import dev.otectus.mcacrime.economy.TransactionReason;
import dev.otectus.mcacrime.state.world.CrimeWorldData;
import dev.otectus.mcacrime.state.world.PropertyEscrow;
import dev.otectus.mcacrime.state.world.PropertyLot;
import dev.otectus.mcacrime.state.world.ServerMutationGate;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
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
            warnOperator(player, server);
            deliverEscrow(player, server);
            dev.otectus.mcacrime.bounty.BountyService.collect(player);
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
        // Read through the policy, not the arrest phases alone: a player who logs back in still held
        // in somebody's rope keeps the penalty, and one whom neither source holds any longer loses a
        // modifier that nothing else would ever have taken off.
        if (dev.otectus.mcacrime.enforcement.RestraintPolicy.effective(player).isEmpty()) {
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

    /**
     * Tells whoever can act on it that this session is read-only.
     *
     * <p>Permission level 2 and above only. An ordinary player learns it the moment they try to do
     * something, from the refusal itself; an operator needs to know before anybody reports that crime
     * has stopped working, because the fix is theirs -- restore a backup, or update the mod.
     */
    private static void warnOperator(ServerPlayer player, MinecraftServer server) {
        if (ServerMutationGate.allows(server) || !player.hasPermissions(2)) {
            return;
        }
        player.sendSystemMessage(Component.translatable("mcacrime.readonly"));
    }

    /**
     * Hands over anything this player is owed and could not be given at the time.
     *
     * <p>Called on login and after confirmed thief death for owners who are online and alive.
     * What does not fit remains in escrow for a later login. An ambiguous external result records
     * a receipt and suspends automatic retry until it can be reconciled.
     */
    public static void deliverEscrow(ServerPlayer player, MinecraftServer server) {
        if (player == null || !player.isAlive() || !ServerMutationGate.allows(server)) return;
        CrimeWorldData data = CrimeWorldData.get(server);
        if (data.propertyEscrowFor(player.getUUID()).isEmpty()) {
            return;
        }
        int closed = PropertyEscrow.deliverPending(data, player.getUUID(), lot -> handover(player, lot), server.overworld().getGameTime());
        if (closed > 0) {
            player.sendSystemMessage(Component.translatable("mcacrime.escrow.delivered", closed));
        }
    }

    /**
     * One lot into one player's inventory or balance.
     *
     * <p>{@code Inventory.add} shrinks the stack it is handed and leaves the overflow in it, so what
     * comes back from this is literally what the inventory refused. The currency adapter must
     * confirm its credit; a failure is ambiguous and must not be retried automatically.
     */
    private static PropertyLot handover(ServerPlayer player, PropertyLot lot) {
        var currency = Currencies.active();
        if (lot.currency() > 0 && !lot.providerId().isEmpty()
                && !lot.providerId().equals(currency.id().toString())) return lot;
        CompoundTag stillOwed = null;
        if (lot.hasStack()) {
            ItemStack stack = lot.stack();
            if (stack.isEmpty()) return lot; // A missing item mod cannot erase the saved stack.
            player.getInventory().add(stack);
            if (!stack.isEmpty()) stillOwed = stack.save(new CompoundTag());
        }
        long owedCurrency = lot.currency();
        if (owedCurrency > 0L) {
            if (!currency.tryCredit(player, owedCurrency, TransactionReason.RECOVERY))
                throw new IllegalStateException("Currency provider did not confirm property delivery");
            owedCurrency = 0L;
        }
        if (java.util.Objects.equals(stillOwed, lot.stackTag()) && owedCurrency == lot.currency()) return lot;
        return lot.remaining(stillOwed, owedCurrency);
    }
}
