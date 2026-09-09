package dev.otectus.mcacrime.mug.npc;

import dev.otectus.mcacrime.McaCrimeConfig;
import dev.otectus.mcacrime.state.world.CrimeWorldData;
import dev.otectus.mcacrime.state.world.StolenGoodsRecord;
import dev.otectus.mcacrime.util.CrimeDebug;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * A guard hands back what the thief was carrying, to whoever is standing there to receive it
 * (spec §"Guards and thief arrests").
 *
 * <p>This exists to remove an incentive rather than to add a convenience. With death as the only
 * recovery path, the correct play on catching a thief is to kill the prisoner the guard has just
 * cuffed — the system would reward murdering somebody in restraints. Returning property on arrest
 * makes the lawful outcome the profitable one.
 *
 * <p>Provenance moves into owner escrow before delivery, preserving the recorded currency provider
 * and retaining ambiguous failures for reconciliation. Owners who are offline or too far away keep
 * their claim untouched. A later death finds only property the arrest did not already move to escrow.
 */
public final class StolenGoodsReturn {

    private StolenGoodsReturn() {
    }

    /**
     * Which owners get their property now and which keep their claim, as a pure function.
     *
     * @param onlineDistances distance in blocks from the arrest for each owner who is online in the
     *                        arresting level; an owner absent from the map is offline or elsewhere
     * @param radius          the configured return radius; owners beyond it are kept, not returned
     */
    public record Partition(List<UUID> returned, List<UUID> kept) {
    }

    public static Partition partition(Collection<UUID> owners, Map<UUID, Double> onlineDistances,
                                      double radius) {
        List<UUID> returned = new ArrayList<>();
        List<UUID> kept = new ArrayList<>();
        if (owners == null) {
            return new Partition(List.of(), List.of());
        }
        Map<UUID, Double> distances = onlineDistances == null ? Map.of() : onlineDistances;
        // A LinkedHashSet rather than the raw collection: one owner can be behind several ledger
        // entries, and a duplicate here would mean two messages and one delivery.
        for (UUID owner : new LinkedHashSet<>(owners)) {
            if (owner == null) {
                continue;
            }
            Double distance = distances.get(owner);
            if (distance != null && distance <= radius) {
                returned.add(owner);
            } else {
                kept.add(owner);
            }
        }
        return new Partition(List.copyOf(returned), List.copyOf(kept));
    }

    /**
     * Returns everything this thief was holding for any victim within {@code stolenGoodsReturnRadius}
     * of the arrest.
     *
     * @return how many ledger entries moved into owner escrow (delivery may still be pending)
     */
    public static int onArrest(MinecraftServer server, ServerLevel level, UUID thief, Vec3 at) {
        if (server == null || level == null || thief == null || at == null
                || !McaCrimeConfig.COMMON.returnStolenGoodsOnArrest.get()) {
            return 0;
        }
        List<StolenGoodsRecord> held = CrimeWorldData.get(server).stolenGoodsByThief(thief);
        if (held.isEmpty()) {
            return 0;
        }
        Set<UUID> owners = new LinkedHashSet<>();
        for (StolenGoodsRecord record : held) {
            owners.add(record.owner());
        }

        double radius = McaCrimeConfig.COMMON.stolenGoodsReturnRadius.get();
        Map<UUID, Double> distances = new LinkedHashMap<>();
        for (UUID owner : owners) {
            ServerPlayer player = server.getPlayerList().getPlayer(owner);
            if (player != null && player.isAlive() && player.level() == level) {
                distances.put(owner, Math.sqrt(player.distanceToSqr(at.x, at.y, at.z)));
            }
        }

        int returned = 0;
        for (UUID owner : partition(owners, distances, radius).returned()) {
            ServerPlayer player = server.getPlayerList().getPlayer(owner);
            if (player == null) {
                continue; // logged out between the distance check and here; the claim survives
            }
            returned += StolenGoodsLedger.escrowForOwner(CrimeWorldData.get(server), thief, owner, level.getGameTime());
            dev.otectus.mcacrime.engine.CrimeReconciler.deliverEscrow(player, server);
        }
        if (returned > 0) {
            CrimeDebug.crime("arrest of thief {} returned {} ledger entr(ies) to nearby owners",
                    thief, returned);
        }
        return returned;
    }

}
