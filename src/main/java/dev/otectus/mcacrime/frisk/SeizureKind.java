package dev.otectus.mcacrime.frisk;

/**
 * Why something left somebody's pockets (M5.3, spec §11.1).
 *
 * <p>The specification's central complaint about the upstream frisking screen is that it treats
 * different events as one. These are the two that remain now that a search moves property into the
 * searcher's own inventory rather than a box: each has its own legal consequence and its own
 * destination, and nothing downstream has to guess which happened. Going through your own pockets
 * is not a search at all and never opens one.
 */
public enum SeizureKind {

    /**
     * A guard or custodian taking property from somebody they lawfully hold.
     *
     * <p>Goes to the property escrow with a durable lot owed back to the subject, so custody ending,
     * bail being paid or a pardon all return it through the recovery ledger that already exists.
     */
    LAWFUL_SEARCH,

    /**
     * Taking from somebody the taker has no legal claim over.
     *
     * <p>Theft, and filed as one: the stolen-goods ledger records it, witnesses see it, and the
     * existing recovery path can give it back. The searcher's own inventory is where it physically
     * goes, and the ledger row is what makes that a theft rather than a transfer.
     */
    CRIMINAL_SEIZURE;

    /** Whether this kind is answerable to the property escrow. */
    public boolean escrowed() {
        return this == LAWFUL_SEARCH;
    }

    /** Whether this kind files a crime. */
    public boolean criminal() {
        return this == CRIMINAL_SEIZURE;
    }

    /** The lang key naming the event on screen and in the log. */
    public String messageKey() {
        return "mcacrime.frisk.seizure." + name().toLowerCase(java.util.Locale.ROOT);
    }
}
