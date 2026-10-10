package dev.otectus.mcacrime.mug.npc;

import dev.otectus.mcacrime.engine.CrimeReconciler;
import dev.otectus.mcacrime.state.world.CrimeWorldData;
import dev.otectus.mcacrime.state.world.ServerMutationGate;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;

/**
 * Confirmed death moves stolen property to its owners before trying any delivery.
 *
 * <p>Only for a thief who holds it in the ledger alone, which is every NPC thief. A player who took
 * something -- through a criminal search, or by taking another victim's property off a thief (0.7.7) --
 * carries the item itself, and the game's own death rules drop or keep it; escrowing the ledger copy as
 * well would hand the owner a second one. Their record stays as provenance until it expires.
 */
public final class StolenGoodsRecovery {
    private StolenGoodsRecovery() {}

    public static void confirmedDeath(ServerLevel level, LivingEntity thief) {
        var server = level.getServer();
        if (!ServerMutationGate.allows(server) || thief instanceof net.minecraft.world.entity.player.Player) return;
        var data = CrimeWorldData.get(server);
        var owners = data.stolenGoodsByThief(thief.getUUID()).stream().map(record -> record.owner()).distinct().toList();
        StolenGoodsLedger.escrowAll(data, thief.getUUID(), level.getGameTime());
        for (var owner : owners) {
            var player = server.getPlayerList().getPlayer(owner);
            if (player != null && player.isAlive()) CrimeReconciler.deliverEscrow(player, server);
        }
    }
}
