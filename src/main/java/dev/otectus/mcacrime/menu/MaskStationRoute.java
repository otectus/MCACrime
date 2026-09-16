package dev.otectus.mcacrime.menu;

import net.minecraft.world.inventory.ClickType;

import javax.annotation.Nullable;

/**
 * Every way a player can try to get something out of (or into) the Mask Station's result slot, and
 * which of them the station honours (0.7.2 §8.3).
 *
 * <p>Spec §8.3 asks for each route to be either traced and tested or <em>explicitly</em> denied,
 * because the dangerous case is the untested fallback: a virtual preview that some route happens to
 * treat as a real stack becomes a free mask. So the table is exhaustive and lives here, away from the
 * menu, where a test can read it without a world.
 *
 * <p>The three allowed routes are the three that end in one owner committing one craft: left click,
 * right click (which takes the same single unstackable mask), and shift-click, which repeats
 * individually validated crafts under a bound. Everything else is refused — not because it could not
 * be made to work, but because each would need its own capacity and debit story, and an unpaid one is
 * indistinguishable from duplication.
 */
public enum MaskStationRoute {

    /** Left click with an empty or matching cursor. One craft. */
    PICKUP(true),
    /** Right click. Takes the single mask, exactly like {@link #PICKUP}. */
    RIGHT_CLICK(true),
    /** Shift-click. A bounded batch of individually validated crafts. */
    SHIFT_CLICK(true),
    /** Number keys 1-9: a swap with a hotbar slot. Denied — a swap would place into the result slot. */
    HOTBAR_SWAP(false),
    /** The off-hand swap key. Denied for the same reason. */
    OFFHAND_SWAP(false),
    /** Q on the result slot. Denied: dropping has no capacity story and no cursor to refuse it. */
    DROP(false),
    /** Click-drag distribution. Denied: a preview is not a stack to spread. */
    DRAG(false),
    /** Double click to collect matching items. Denied: it would mint a second mask to "collect". */
    DOUBLE_CLICK(false),
    /** Creative middle click. Denied: the preview is not an item the client may clone. */
    CREATIVE_CLONE(false),
    /** Putting something into the result slot. Denied: nothing is stored there. */
    PLACE(false);

    private final boolean allowed;

    MaskStationRoute(boolean allowed) {
        this.allowed = allowed;
    }

    /** Whether the result slot honours this route. */
    public boolean allowed() {
        return allowed;
    }

    /**
     * The route a vanilla click on the result slot represents.
     *
     * @param button the raw button/slot argument vanilla passes alongside {@code type}
     * @return the route, or {@code null} when this click does not touch the result slot at all
     */
    @Nullable
    public static MaskStationRoute of(ClickType type, int button) {
        return switch (type) {
            case PICKUP -> button == 1 ? RIGHT_CLICK : PICKUP;
            case QUICK_MOVE -> SHIFT_CLICK;
            // Vanilla spends button 40 on the off-hand and 0-8 on the hotbar for a SWAP.
            case SWAP -> button == 40 ? OFFHAND_SWAP : HOTBAR_SWAP;
            case THROW -> DROP;
            case QUICK_CRAFT -> DRAG;
            case PICKUP_ALL -> DOUBLE_CLICK;
            case CLONE -> CREATIVE_CLONE;
        };
    }
}
