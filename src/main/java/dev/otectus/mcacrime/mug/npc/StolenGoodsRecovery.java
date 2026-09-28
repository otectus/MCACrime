package dev.otectus.mcacrime.mug.npc;

import dev.otectus.mcacrime.engine.CrimeReconciler;
import dev.otectus.mcacrime.state.world.CrimeWorldData;
import dev.otectus.mcacrime.state.world.ServerMutationGate;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;

/** Confirmed death moves stolen property to its owners before trying any delivery. */
public final class StolenGoodsRecovery {
    private StolenGoodsRecovery() {}

    public static void confirmedDeath(ServerLevel level, LivingEntity thief) {
        var server = level.getServer();
        if (!ServerMutationGate.allows(server)) return;
        var data = CrimeWorldData.get(server);
        var held = data.stolenGoodsByThief(thief.getUUID());
        var owners = held.stream().map(record -> record.owner()).distinct().toList();
        StolenGoodsLedger.escrowAll(data, thief.getUUID(), level.getGameTime());
        for (var entry : held) if (data.stolenGoods(entry.transactionId()) == null)
            dev.otectus.mcacrime.news.CrimeNewsService.recovered(server, entry.transactionId(), thief.getUUID(), entry.owner());
        for (var owner : owners) {
            var player = server.getPlayerList().getPlayer(owner);
            if (player != null && player.isAlive()) CrimeReconciler.deliverEscrow(player, server);
        }
    }
}
