package dev.otectus.mcacrime.captivity;

/**
 * The restraint securing a captive (spec §8.3). Distinct strengths are what make rope, cuffs, and locked
 * cuffs worth having separately; the actual escape-difficulty numbers are config (resolved at call time by
 * the capture/confine services), not baked here — so this stays a pure enum like {@link
 * dev.otectus.mcacrime.jail.JailContainmentMode}.
 *
 * <ul>
 *   <li>{@link #NONE} — no restraint (an unrestrained captive, e.g. a soft-confined jail prisoner).</li>
 *   <li>{@link #ROPE} — basic; broad mod compatibility via the {@code forge:rope} item tag; easiest escape.</li>
 *   <li>{@link #CUFFS} — the law/criminal arrest restraint; harder to escape.</li>
 *   <li>{@link #LOCKED_CUFFS} — strongest; needs a key/lockpick/rescue (Locks Reforged is a Phase 7 seam).</li>
 * </ul>
 */
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
