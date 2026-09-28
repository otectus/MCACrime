package dev.otectus.mcacrime.jail;

/** Why a jail sentence ended (spec §7). Carried by {@code PlayerReleasedFromJailEvent} and used to pick feedback. */
public enum ReleaseReason {
    /** The online-tick sentence reached zero. */
    SENTENCE_SERVED,
    /** The real-online-time captivity cap (§7.2) forced release. */
    CAPTIVITY_CAP,
    /** An operator ran {@code /crime release} (or an admin API). */
    ADMIN,
    /** A pardon resolved the record (Phase 5 hook). */
    PARDON,
    /** The remainder of the sentence was bought out under {@code enableBail}. */
    BAILED,
    /** The jail anchor/dimension became unusable and no fallback existed — released to avoid a softlock. */
    INVALID_JAIL,
    /**
     * A capital sentence was carried out at a device (0.7.5 §3.19).
     *
     * <p>The only release reason that is not a release: the prisoner is dead, the custody ends under
     * {@code CustodyReleaseReason.CAPTIVE_DIED} beside it, and the sentence closes once through
     * {@code ledger/CapitalDeathOutcome}. Nothing reaches this reason without a confirmed death.
     */
    EXECUTED
}
