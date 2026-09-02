package dev.otectus.mcacrime;

import dev.otectus.mcacrime.client.screen.widget.CrimeRowList;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Guards the one piece of list arithmetic that fails silently.
 *
 * <p>{@code AbstractSelectionList.getEntryAtPosition} is final. It centres the clickable band on the
 * list and refuses any click at or beyond the scrollbar, so a row drawn wider than that band has a
 * strip down its right-hand edge that is drawn but not clickable. Nothing throws, nothing logs, and
 * the screen simply ignores some of the clicks aimed at it — which reads as a broken mod rather than
 * as an arithmetic slip. This got the width wrong once already while the sheet was being laid out.
 *
 * <p>Both panel widths in the mod are checked, because the margin depends on the width and a value
 * that is safe at 232 is not automatically safe at 260.
 */
class CrimeRowListGeometryTest {

    /** CrimeInteractionScreen's list: its 232px panel, less the 6px inset on each side. */
    private static final int ACTION_LIST_W = 232 - 12;
    /** CaseDossierScreen's, inset the same way from 260px. */
    private static final int DOSSIER_LIST_W = 260 - 12;

    @Test
    void rowsNeverRunUnderTheScrollbar() {
        for (int width : new int[] {ACTION_LIST_W, DOSSIER_LIST_W}) {
            for (int left : new int[] {0, 7, 123, 640}) {
                assertTrue(CrimeRowList.rowRight(left, width) <= CrimeRowList.scrollbarX(left, width),
                        "a row in a " + width + "px list at x=" + left + " reaches "
                                + CrimeRowList.rowRight(left, width) + ", past the scrollbar at "
                                + CrimeRowList.scrollbarX(left, width) + "; the overlap would be drawn "
                                + "but not clickable");
            }
        }
    }

    @Test
    void rowsAreWideEnoughToBeWorthDrawing() {
        // A guard that passes by making rows vanishingly narrow would be no guard at all.
        for (int width : new int[] {ACTION_LIST_W, DOSSIER_LIST_W}) {
            assertTrue(CrimeRowList.rowWidth(width) >= width - 20,
                    "a " + width + "px list gives its rows only " + CrimeRowList.rowWidth(width)
                            + "px; the margins have grown out of proportion to the panel");
        }
    }

    @Test
    void rowsAreCentredWithinAPixel() {
        // Measured to the outer edge of the list, not to the scrollbar. The clickable band is centred
        // on the full width, so the scrollbar sits inside the right-hand margin rather than beside it
        // — which is what makes the panel look balanced despite the rows stopping short on one side.
        for (int width : new int[] {ACTION_LIST_W, DOSSIER_LIST_W}) {
            int left = 0;
            int rowLeft = CrimeRowList.rowRight(left, width) - CrimeRowList.rowWidth(width);
            int leftMargin = rowLeft - left;
            int rightMargin = left + width - CrimeRowList.rowRight(left, width);
            assertTrue(Math.abs(leftMargin - rightMargin) <= 1,
                    "rows in a " + width + "px list sit " + leftMargin + "px from the left edge and "
                            + rightMargin + "px from the right; the panel would look lopsided");
        }
    }
}
