package dev.otectus.mcacrime.menu;

/**
 * Where the frisking screen puts its slots (M5.2).
 *
 * <p>Pure arithmetic, so the geometry can be asserted without a client: a subject with forty-one
 * searchable slots and a villager with six both have to produce a screen that fits on the smallest
 * supported window and never overlaps its own title.
 */
public final class FriskingLayout {

    /** Slots per row, matching every vanilla container. */
    public static final int COLUMNS = 9;

    /** One slot's pitch in pixels. */
    public static final int SLOT = 18;

    /** The left inset of the first column. */
    public static final int LEFT = 8;

    /** The top inset of the first row, leaving room for the title. */
    public static final int TOP = 18;

    /** The most rows the screen will draw before it stops growing. Six is a double chest. */
    public static final int MAX_ROWS = 6;

    /** The panel's width, which never changes. */
    public static final int WIDTH = 176;

    private FriskingLayout() {
    }

    /** How many rows {@code slots} needs, bounded. */
    public static int rows(int slots) {
        int needed = (Math.max(0, slots) + COLUMNS - 1) / COLUMNS;
        return Math.max(1, Math.min(MAX_ROWS, needed));
    }

    /** How many slots actually fit on the screen. */
    public static int visible(int slots) {
        return Math.min(Math.max(0, slots), MAX_ROWS * COLUMNS);
    }

    public static int x(int index) {
        return LEFT + (index % COLUMNS) * SLOT;
    }

    public static int y(int index) {
        return TOP + (index / COLUMNS) * SLOT;
    }

    /** The panel's height for a given slot count, including the footer line. */
    public static int height(int slots) {
        return TOP + rows(slots) * SLOT + 18;
    }
}
