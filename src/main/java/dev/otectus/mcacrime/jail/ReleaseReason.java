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
    /** The jail anchor/dimension became unusable and no fallback existed — released to avoid a softlock. */
    INVALID_JAIL
}
