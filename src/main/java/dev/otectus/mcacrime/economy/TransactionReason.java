package dev.otectus.mcacrime.economy;

/**
 * Why currency moved (0.5.1). Every {@link Currency} debit and credit names one.
 *
 * <p>An economy mod implementing {@code Currency} needs this: a bank has to be able to write "fine"
 * in a ledger line rather than "MCA: Crime removed 40 of something". It also makes the emerald
 * default's logging useful without any of the callers having to pass a string.
 */
public enum TransactionReason {
    FINE,
    BAIL,
    RANSOM,
    MUG,
    THEFT,
    FENCE_TRADE,
    BOUNTY,
    RECOVERY,
    RESTITUTION,
    COMMAND,
    OTHER
}
