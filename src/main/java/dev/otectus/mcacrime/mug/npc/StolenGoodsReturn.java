package dev.otectus.mcacrime.mug.npc;

import dev.otectus.mcacrime.McaCrimeConfig;
import dev.otectus.mcacrime.economy.Currencies;
import dev.otectus.mcacrime.economy.Currency;
import dev.otectus.mcacrime.economy.TransactionReason;
import dev.otectus.mcacrime.state.world.CrimeWorldData;
import dev.otectus.mcacrime.state.world.StolenGoodsRecord;
import dev.otectus.mcacrime.util.CrimeDebug;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
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
 * <p>The return is transactional in exactly the way death recovery is, and for the same reason:
 * {@link StolenGoodsLedger#claimForOwner} removes the entries it hands back, so an arrest cannot pay
 * a victim who has already been paid, and {@code StolenGoodsRecovery}'s later {@code claimAll} on the
 * same thief can only find what this call left behind. Owners who are offline or too far away keep
 * their claim untouched: nothing is destroyed by not being nearby.
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
     * @return how many ledger entries were handed back
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
            returned += give(player, StolenGoodsLedger.claimForOwner(server, thief, owner));
        }
        if (returned > 0) {
            CrimeDebug.crime("arrest of thief {} returned {} ledger entr(ies) to nearby owners",
                    thief, returned);
        }
        return returned;
    }

    /** Hands one owner their entries back. Anything that will not fit lands at their feet. */
    private static int give(ServerPlayer owner, List<StolenGoodsRecord> claimed) {
        Currency currency = Currencies.active();
        int handed = 0;
        for (StolenGoodsRecord record : claimed) {
            if (record.hasStack()) {
                ItemStack stack = record.stack();
                if (!stack.isEmpty()) {
                    Component name = stack.getHoverName();
                    if (!owner.getInventory().add(stack)) {
                        // A full inventory must not silently eat the return: the entry is already out
                        // of the ledger, so the only safe place left is the floor.
                        owner.drop(stack, false);
                    }
                    owner.sendSystemMessage(Component.translatable("mcacrime.npc_mug.returned", name));
                }
            }
            long amount = Math.max(0L, record.currency());
            if (amount > 0L) {
                currency.credit(owner, amount, TransactionReason.RECOVERY);
                owner.sendSystemMessage(Component.translatable("mcacrime.npc_mug.returned",
                        currency.format(amount)));
            }
            handed++;
        }
        return handed;
    }
}
