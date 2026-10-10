package dev.otectus.mcacrime.compat;

/**
 * One independently-bindable piece of the MCA Capitals integration (0.7.8).
 *
 * <p>The unit of failure for the seam, exactly as {@link TownsteadCapability} is for Townstead. A
 * Capitals point release that moves one accessor disables the one capability that read it: the royal
 * decree feature needs {@link #REGISTRY}, {@link #AUTHORITY} and {@link #TITLES} together, so losing
 * any of the three turns decrees off with a reason, while a moved chronicle method only stops the
 * chronicle line. Nothing here ever throws into gameplay.
 *
 * <p>Every capability is a <em>read</em> of Capitals' own state, except {@link #CHRONICLE}, which writes
 * one line into a capital's chronicle when the operator has switched that on. MCA: Crime never writes
 * Capitals' justice, warrant, title or succession data.
 *
 * <p>The ids appear in operator output and validation messages, so renaming one is user-visible.
 */
public enum CapitalsCapability {

    REGISTRY("registry",
            "Which capital is seated in a village, its id, dimension and state."),
    AUTHORITY("authority",
            "Who sits on a capital's throne, and the rank Capitals resolves for a player there."),
    TITLES("titles",
            "A player's granted, marriage and dowager noble titles in one capital."),
    COURT("court",
            "The Hand, Lord Commander, Master of Laws, Herald and the royal guard of a capital."),
    CHRONICLE("chronicle",
            "Writing one line into a capital's chronicle, with or without the herald."),
    PLAYER_WARRANTS("player_warrants",
            "Whether Capitals itself holds a warrant against a player in a capital (read only).");

    private final String id;
    private final String description;

    CapitalsCapability(String id, String description) {
        this.id = id;
        this.description = description;
    }

    /** The stable id used in diagnostics and validation messages. */
    public String id() {
        return id;
    }

    /** One line saying what this capability reads, for {@code /crime debug capitals}. */
    public String description() {
        return description;
    }
}
