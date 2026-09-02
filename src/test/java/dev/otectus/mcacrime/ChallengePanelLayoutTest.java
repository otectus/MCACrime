package dev.otectus.mcacrime;

import dev.otectus.mcacrime.client.screen.ChallengePanelLayout;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The guard challenge panel's geometry.
 *
 * <p>These are the assertions that would have caught the shipped bug. The countdown was drawn at
 * {@code panelHeight - 14}, which with four buttons landed inside the last one, so the timer the screen
 * exists to show was invisible in the most common case. A comment saying "leave room for the countdown"
 * does not survive somebody adding a fifth response; this does.
 */
class ChallengePanelLayoutTest {

    private static final int LINE_HEIGHT = 9;

    @Test
    void theStatusLineNeverOverlapsTheButtons() {
        for (int buttons = 1; buttons <= 6; buttons++) {
            ChallengePanelLayout layout = ChallengePanelLayout.of(buttons);
            assertTrue(layout.statusY() + LINE_HEIGHT <= layout.firstButtonY(),
                    "the countdown runs into the first button with " + buttons + " responses");
        }
    }

    @Test
    void everyButtonFitsInsideThePanel() {
        for (int buttons = 1; buttons <= 6; buttons++) {
            ChallengePanelLayout layout = ChallengePanelLayout.of(buttons);
            int lastBottom = layout.buttonY(buttons - 1) + layout.buttonHeight();
            assertTrue(lastBottom <= layout.panelHeight(),
                    "the last of " + buttons + " buttons overflows the panel");
        }
    }

    @Test
    void buttonsDoNotOverlapEachOther() {
        ChallengePanelLayout layout = ChallengePanelLayout.of(4);
        for (int i = 1; i < 4; i++) {
            assertTrue(layout.buttonY(i) >= layout.buttonY(i - 1) + layout.buttonHeight(),
                    "buttons " + (i - 1) + " and " + i + " overlap");
        }
    }

    @Test
    void theTextLinesAboveTheButtonsAreInReadingOrder() {
        ChallengePanelLayout layout = ChallengePanelLayout.of(4);
        assertTrue(layout.titleY() < layout.guardNameY());
        assertTrue(layout.guardNameY() < layout.chargesY());
        assertTrue(layout.chargesY() < layout.statusY());
    }

    /** The panel grows with the response count rather than always reserving room for the largest form. */
    @Test
    void thePanelIsSizedToWhatItActuallyShows() {
        assertTrue(ChallengePanelLayout.of(3).panelHeight() < ChallengePanelLayout.of(4).panelHeight());
        assertEquals(ChallengePanelLayout.WIDTH, ChallengePanelLayout.of(4).panelWidth());
    }

    @Test
    void buttonsAreInsetFromBothPanelEdgesEqually() {
        ChallengePanelLayout layout = ChallengePanelLayout.of(4);
        assertEquals(layout.panelWidth() - 2 * layout.contentLeft(), layout.buttonWidth());
    }
}
