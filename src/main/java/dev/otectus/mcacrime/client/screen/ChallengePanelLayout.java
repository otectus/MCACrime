package dev.otectus.mcacrime.client.screen;

/**
 * Where everything sits on the guard challenge panel.
 *
 * <p>Extracted from the screen because the bug it fixes was arithmetic, not rendering. The countdown
 * was drawn at {@code panelTop + PANEL_H - 14}, which with four buttons is {@code +136} — inside the
 * Refuse button's box at {@code +128..+148} — and it was drawn before {@code super.render()}, so the
 * widget painted over it anyway. The class javadoc claimed the countdown was "the most prominent thing
 * on the panel" while it was in fact invisible in the most common case.
 *
 * <p>Numbers alone drift back. The codebase already treats layout as a pure function it can assert
 * against ({@code CrimeRowListGeometryTest}, {@code HudAnchorTest}), so the invariant that matters —
 * the countdown never overlaps the buttons, and the buttons never overflow the panel — is a test
 * rather than a comment.
 *
 * <p>The countdown moved up beside the charge block instead of the panel being made taller alone. It
 * qualifies the charges ("this is what you are answering, and this is how long you have"), and putting
 * it there leaves the button stack a clean slab that a fifth response could be added to without
 * anything colliding.
 */
public record ChallengePanelLayout(int panelWidth, int panelHeight, int titleY, int guardNameY,
                                   int chargesY, int statusY, int firstButtonY, int buttonSpacing,
                                   int buttonHeight) {

    public static final int WIDTH = 220;
    private static final int TITLE_Y = 8;
    private static final int GUARD_NAME_Y = 20;
    private static final int CHARGES_Y = 34;
    private static final int STATUS_Y = 46;
    private static final int FIRST_BUTTON_Y = 62;
    private static final int BUTTON_SPACING = 22;
    private static final int BUTTON_HEIGHT = 20;
    /** Space left below the last button, so the panel border never crowds it. */
    private static final int BOTTOM_MARGIN = 10;

    /** The layout for a panel with {@code buttonCount} responses on it. */
    public static ChallengePanelLayout of(int buttonCount) {
        int buttons = Math.max(1, buttonCount);
        int lastButtonBottom = FIRST_BUTTON_Y + (buttons - 1) * BUTTON_SPACING + BUTTON_HEIGHT;
        return new ChallengePanelLayout(WIDTH, lastButtonBottom + BOTTOM_MARGIN, TITLE_Y, GUARD_NAME_Y,
                CHARGES_Y, STATUS_Y, FIRST_BUTTON_Y, BUTTON_SPACING, BUTTON_HEIGHT);
    }

    /** The y of button {@code index}, counting from zero. */
    public int buttonY(int index) {
        return firstButtonY + index * buttonSpacing;
    }

    /** The width a button spans, inset from the panel edges. */
    public int buttonWidth() {
        return panelWidth - 24;
    }

    /** The left inset every line of text and every button starts at. */
    public int contentLeft() {
        return 12;
    }
}
