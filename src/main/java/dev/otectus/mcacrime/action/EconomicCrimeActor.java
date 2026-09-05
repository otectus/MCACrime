package dev.otectus.mcacrime.action;

import dev.otectus.mcacrime.economy.TransactionReason;

/**
 * An actor that can hold and move money (0.5.1).
 *
 * <p>Split out of {@link CrimeActor} rather than added to it because the two capabilities are not the
 * same question: everything that acts has an identity and a position, but a currency balance is
 * something a player and a villager both have <em>through different machinery</em> — the player through
 * {@code Currencies.active()}, the villager through the finite {@code VillagerPurse} its profile owns.
 * Handlers that move money ask for this interface and stop caring which side of that they are on,
 * which is the whole reason NPC-on-player theft can reuse the player-on-NPC mugging arithmetic instead
 * of growing a second, unbounded copy of it.
 */
public interface EconomicCrimeActor extends CrimeActor {

    /** What this actor can currently pay with. Never negative. */
    long currencyBalance();

    /**
     * Removes up to {@code amount} and returns how much actually moved, exactly like
     * {@code Currency.debit}: a purse with three emeralds in it answers a demand for eight with three.
     */
    long debitCurrency(long amount, TransactionReason reason);

    /**
     * Adds up to {@code amount} and returns how much was actually taken. A villager purse has a
     * capacity, so the answer can be short; a player's cannot, so it is not.
     */
    long creditCurrency(long amount, TransactionReason reason);
}
