package dev.otectus.mcacrime.economy.account;

import dev.otectus.mcacrime.economy.TransactionReason;

/**
 * Both ends of one transfer, as the only thing {@link EconomicTransactionService} is allowed to touch.
 *
 * <p>The service used to reach for {@code Currencies.active()} and a live {@code ServerPlayer} directly,
 * which made the ordering it exists to guarantee — debit, persist, then credit — impossible to assert
 * without a running server holding real emeralds. Behind this seam the ordering is testable, and the
 * one case that matters most is finally reachable: a credit that <em>throws</em>, which no live
 * currency can be asked to do on demand.
 *
 * <p>Not a functional interface: a transfer has a source and a destination and they are different
 * parties. One object standing for the pair is what lets a caller decide, in one place, where the
 * money comes from and where it goes.
 */
public interface PurseAccess {

    /** What the source can currently afford. Never negative. */
    long available();

    /**
     * Removes up to {@code amount} from the source and returns how much was actually removed.
     * A return of zero means nothing moved and the transfer may be safely refused.
     */
    long debit(long amount, TransactionReason reason);

    /**
     * Gives {@code amount} to the destination. False means it did not arrive; an exception means the
     * same thing and is caught by the caller, because a currency provider is third-party code and
     * "throws instead of returning false" is a thing third-party code does.
     */
    boolean credit(long amount, TransactionReason reason);
}
