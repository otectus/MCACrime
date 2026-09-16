package dev.otectus.mcacrime.job;

import org.jetbrains.annotations.Nullable;

import java.util.Locale;

/**
 * What a record's remembered previous profession actually is (0.7.2 §9.1, JOB-11).
 *
 * <p>Schema 11 stored one nullable string with three meanings crammed into it: {@code null} for "never
 * took one away", {@code ""} for "there was one but it could not be read", and an id for the real
 * answer. Two of those are indistinguishable to anything that reads the field defensively, so a
 * rollback could not tell "restore nothing" from "we never found out". The kind is explicit here and
 * the id is only meaningful alongside {@link #ID}.
 */
public enum HistoricalProfessionKind {

    /** Nothing was displaced — the villager had no profession, or none was taken from them. */
    NONE,
    /** A profession existed but could not be read, so it must not be invented on the way back. */
    UNREADABLE,
    /** A registry id was captured. */
    ID;

    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static HistoricalProfessionKind parse(@Nullable String id) {
        if (id == null || id.isBlank()) {
            return NONE;
        }
        try {
            return valueOf(id.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return NONE;
        }
    }

    /**
     * The kind a schema-11 {@code previousProfessionId} string meant.
     *
     * <p>The legacy encoding is preserved exactly rather than normalised: a blank string was written
     * on purpose to mean "had no readable profession", and reading it as {@link #NONE} would turn a
     * deliberate record of ignorance into a claim that nothing was displaced.
     */
    public static HistoricalProfessionKind ofLegacy(@Nullable String legacy) {
        if (legacy == null) {
            return NONE;
        }
        return legacy.isBlank() ? UNREADABLE : ID;
    }
}
