package dev.otectus.mcacrime.action;

import dev.otectus.mcacrime.economy.Currencies;
import dev.otectus.mcacrime.economy.TransactionReason;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Inventory;

import java.util.UUID;

/**
 * The only {@link CrimeActor} implementation that exists today. Deliberately a thin wrapper with no
 * state of its own: an actor is created per entry into the action engine and never stored, so it can
 * never go stale against the player it wraps.
 *
 * <p>A player is the actor that has both optional capabilities: money, through whichever
 * {@code Currency} the server configured, and an inventory. A villager has only the first, which is
 * why they are two interfaces and not one.
 */
public record PlayerActor(ServerPlayer player) implements EconomicCrimeActor, InventoryCrimeActor {

    /** Wraps a player, or returns {@code null} if it is not on a server level. */
    public static PlayerActor of(ServerPlayer player) {
        return player != null && player.level() instanceof ServerLevel ? new PlayerActor(player) : null;
    }

    @Override
    public UUID id() {
        return player.getUUID();
    }

    @Override
    public LivingEntity entity() {
        return player;
    }

    @Override
    public ServerLevel level() {
        return (ServerLevel) player.level();
    }

    @Override
    public ServerPlayer asPlayer() {
        return player;
    }

    @Override
    public void sendMessage(Component message) {
        player.sendSystemMessage(message);
    }

    @Override
    public void sendActionBar(Component message) {
        player.displayClientMessage(message, true);
    }

    @Override
    public Inventory inventory() {
        return player.getInventory();
    }

    @Override
    public long currencyBalance() {
        return Currencies.active().balance(player);
    }

    @Override
    public long debitCurrency(long amount, TransactionReason reason) {
        return amount <= 0L ? 0L : Currencies.active().debit(player, amount, reason);
    }

    /**
     * A player's pockets have no capacity, so the whole amount always lands -- the return value exists
     * for the villager side of the interface, where it does not.
     */
    @Override
    public long creditCurrency(long amount, TransactionReason reason) {
        if (amount <= 0L) {
            return 0L;
        }
        Currencies.active().credit(player, amount, reason);
        return amount;
    }
}
