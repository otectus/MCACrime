package dev.otectus.mcacrime.captivity;

/**
 * Why a captivity ended (spec §8.4). Carried by {@code EntityReleasedFromCaptivityEvent} and used to pick
 * feedback. Mirrors {@link dev.otectus.mcacrime.jail.ReleaseReason}; the captivity set is broader because
 * unlawful custody (kidnapping) has its own exits (escape, rescue, captor-gone, ransom).
 */
public enum CustodyReleaseReason {
    /** A third party freed the captive. */
    RESCUED,
    /** The captive broke free (kidnapping escape is never a crime — spec §8.1). */
    ESCAPED,
    /** The captor logged off / died / changed dimension with no valid hold — guaranteed recovery (§8.4). */
    CAPTOR_GONE,
    /** The real-online-time captivity cap (§7.2) forced release. */
    CAPTIVITY_CAP,
    /** An operator ran {@code /crime release} (the always-works admin backstop). */
    ADMIN,
    /** A ransom was paid (spec §8.5). */
    RANSOM_PAID,
    /** A lawful jail sentence finished (projection cleanup; the jail authority is {@code JailService}). */
    SENTENCE_SERVED,
    /** The captive died; a kidnapping victim is freed rather than left dangling (§8.4). */
    CAPTIVE_DIED;

    public static CustodyReleaseReason parse(String name) {
        try {
            return valueOf(name);
        } catch (IllegalArgumentException e) {
            return ADMIN;
        }
    }
}
