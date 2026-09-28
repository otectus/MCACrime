package dev.otectus.mcacrime.restraint;

import java.util.Locale;
import java.util.Optional;

/**
 * What kind of thing a restraint is, independent of which slot it is worn on (0.7.5 §3.7).
 *
 * <p>The family is the key identity: a key opens a <em>family</em>, not a definition. One
 * {@code handcuffs_key} frees arm cuffs and leg cuffs; a {@code shackles_key} frees neither. Comparing families rather than definition ids is also what keeps "the same cuffs on the
 * legs" from needing a second key item.
 */
public enum RestraintFamily {

    /** Locking metal cuffs. Strong: the arms variant takes the hands away entirely. */
    HANDCUFFS,
    /** Lighter metal restraints. Weaker, and the hands stay useful. */
    SHACKLES,
    /** Tape. Weak, no metal key, and a cutting tool ends it. */
    TAPE,
    /** Head coverings: the bundle hood and its relatives. */
    HOOD,
    /**
     * The pre-0.7.5 rope item's family.
     *
     * <p>No definition uses it. It exists because {@code mcacrime:restraint_rope} stays registered
     * as a legacy conversion carrier (§3.18) and its provenance has to be nameable without
     * pretending a rope was ever one of the nine definitions.
     */
    LEGACY_ROPE;

    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static Optional<RestraintFamily> parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        String name = raw.trim().toUpperCase(Locale.ROOT);
        for (RestraintFamily family : values()) {
            if (family.name().equals(name)) {
                return Optional.of(family);
            }
        }
        return Optional.empty();
    }
}
