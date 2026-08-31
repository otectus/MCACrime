package dev.otectus.mcacrime.economy.account;

import dev.otectus.mcacrime.economy.EmeraldCurrency;
import dev.otectus.mcacrime.state.world.CrimeWorldData;
import net.minecraft.server.level.ServerPlayer;

import java.util.UUID;

/** Sole writer for finite purse/treasury transfers. Transaction ids commit at most once. */
public final class EconomicTransactionService {
    private EconomicTransactionService() {}

    public static synchronized int transferPurseToPlayer(CrimeWorldData world, UUID transactionId,
                                                          VillagerPurse purse, ServerPlayer player,
                                                          int requested) {
        if (requested <= 0 || world.hasTransactionReceipt(transactionId)) return 0;
        int amount = Math.min(purse.balance(), requested);
        if (amount <= 0) return 0;
        // Claim before either side effect. A replay can never repeat the transfer.
        if (!world.recordTransactionReceipt(transactionId)) return 0;
        int debited = purse.withdraw(amount);
        if (debited > 0) EmeraldCurrency.INSTANCE.grant(player, debited);
        world.setDirty();
        return debited;
    }

    public static synchronized boolean transferTreasuryToPlayer(CrimeWorldData world, UUID transactionId,
                                                                 String treasury, long initialBalance,
                                                                 ServerPlayer player, long amount) {
        if (amount < 0L || world.hasTransactionReceipt(transactionId)) return false;
        if (world.treasuryBalance(treasury, initialBalance) < amount) return false;
        if (!world.recordTransactionReceipt(transactionId)) return false;
        if (!world.withdrawTreasury(treasury, amount, initialBalance)) return false;
        EmeraldCurrency.INSTANCE.grant(player, amount);
        return true;
    }

    public static synchronized boolean transferPlayerToPlayer(CrimeWorldData world, UUID transactionId,
                                                               ServerPlayer payer, ServerPlayer recipient,
                                                               long amount) {
        if (amount < 0L || world.hasTransactionReceipt(transactionId)) return false;
        if (EmeraldCurrency.INSTANCE.balance(payer) < amount) return false;
        if (!world.recordTransactionReceipt(transactionId)) return false;
        if (!EmeraldCurrency.INSTANCE.tryCharge(payer, amount)) return false;
        EmeraldCurrency.INSTANCE.grant(recipient, amount);
        return true;
    }
}
