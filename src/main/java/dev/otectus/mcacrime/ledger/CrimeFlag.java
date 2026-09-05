package dev.otectus.mcacrime.ledger;

import javax.annotation.Nullable;

import java.util.EnumSet;
import java.util.Locale;

/**
 * Circumstances of a crime that change how it is handled, carried on the record's {@code context}
 * map under the key {@link #CONTEXT_KEY} (0.5.1).
 *
 * <p>They live in the context rather than as record fields deliberately. {@code CrimeRecord} is a
 * sixteen-field record with its own save/load and call sites in every phase of the mod; widening it
 * to carry a set that only two subsystems read would make every one of those call sites churn for
 * information none of them use. The context map already exists for exactly this, and the encoding is
 * a comma-separated name list so a saved world stays readable by eye.
 */
public enum CrimeFlag {

    /** A witness or a guard saw the act itself, not just its aftermath. */
    CAUGHT_IN_ACT,
    /** The offence is not fine-payable: it ends in custody or not at all. */
    MANDATORY_CUSTODY,
    /** The offender was an NPC, so there is no player capability behind the record. */
    NPC_OFFENDER;

    /** The {@code CrimeRecord.context} key these are stored under. */
    public static final String CONTEXT_KEY = "flags";

    /** Comma-separated lowercase names, in enum order. Empty string for an empty set. */
    public static String encode(EnumSet<CrimeFlag> flags) {
        if (flags == null || flags.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        for (CrimeFlag flag : flags) {
            if (out.length() > 0) {
                out.append(',');
            }
            out.append(flag.name().toLowerCase(Locale.ROOT));
        }
        return out.toString();
    }

    /** Parses {@link #encode}. Unknown or blank names are skipped rather than throwing. */
    public static EnumSet<CrimeFlag> decode(@Nullable String encoded) {
        EnumSet<CrimeFlag> flags = EnumSet.noneOf(CrimeFlag.class);
        if (encoded == null || encoded.isBlank()) {
            return flags;
        }
        for (String part : encoded.split(",")) {
            String name = part.trim().toUpperCase(Locale.ROOT);
            if (name.isEmpty()) {
                continue;
            }
            try {
                flags.add(CrimeFlag.valueOf(name));
            } catch (IllegalArgumentException e) {
                // A flag written by a newer build. Ignoring it is right: this build cannot act on it.
            }
        }
        return flags;
    }
}
