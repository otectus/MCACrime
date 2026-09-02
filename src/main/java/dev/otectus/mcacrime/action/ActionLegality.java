package dev.otectus.mcacrime.action;

/**
 * The broad legality marker shown beside an action row (spec §8.4). Deliberately coarse: it tells a
 * player whether the law would object, not what the exact charge or Heat cost would be. Exact
 * consequences stay server-side so the screen cannot be read as a crime calculator.
 */
public enum ActionLegality {
    /** No offence under any configuration. */
    LAWFUL("lawful", 0x55FF55),
    /** Not itself a crime, but it draws attention or precedes one. */
    SUSPICIOUS("suspicious", 0xFFAA00),
    /** A crime wherever it is witnessed. */
    CRIMINAL("criminal", 0xFF5555),
    /** Legality depends on state the player can see — custody, authority, or an open case. */
    CONTEXTUAL("contextual", 0xAAAAAA);

    private final String lower;
    private final int rgb;

    ActionLegality(String lower, int rgb) {
        this.lower = lower;
        this.rgb = rgb;
    }

    public String lower() {
        return lower;
    }

    /** Row tint for this marker. Client-side use only; the value is not authoritative for anything. */
    public int rgb() {
        return rgb;
    }

    public String labelKey() {
        return "gui.mcacrime.legality." + lower;
    }
}
