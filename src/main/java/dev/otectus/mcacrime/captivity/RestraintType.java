package dev.otectus.mcacrime.captivity;

/**
 * The 0.7.4 restraint enum, kept as a <b>deprecated projection only</b> (0.7.5 §5.1).
 *
 * <p>It was the authority on what was holding a captive. It is not any more: physical restraint is
 * {@code restraint/PhysicalRestraintState} -- nine definitions across three independent body slots,
 * each with its own durability, applier and provenance -- and nothing in the mod writes one of these
 * constants into world data again.
 *
 * <p>Two published API members still name it, which is the whole reason it exists:
 * {@code api/event/EntityKidnappedEvent#getRestraint} and {@code api/model/CustodyView#restraint}.
 * Both are filled by {@code restraint/LegacyRestraintProjection}, which computes the nearest old
 * answer from the real state. {@code restraint/RestraintMigrationReconciler#definitionFor} is the
 * opposite direction, used once per store at the schema 14 to 15 upgrade.
 *
 * <ul>
 *   <li>{@link #NONE} — nothing on the arms.</li>
 *   <li>{@link #ROPE} — projects tape and the bundle hood.</li>
 *   <li>{@link #CUFFS} — projects the shackles family.</li>
 *   <li>{@link #LOCKED_CUFFS} — projects the handcuffs family.</li>
 * </ul>
 */
@Deprecated
public enum RestraintType {
    NONE,
    ROPE,
    CUFFS,
    LOCKED_CUFFS;

    public static RestraintType parse(String name) {
        try {
            return valueOf(name);
        } catch (IllegalArgumentException e) {
            return NONE;
        }
    }

    /** Safe lookup by ordinal (for the display-only captive packet); out-of-range collapses to {@link #NONE}. */
    public static RestraintType byOrdinal(int ordinal) {
        RestraintType[] values = values();
        return ordinal >= 0 && ordinal < values.length ? values[ordinal] : NONE;
    }
}
