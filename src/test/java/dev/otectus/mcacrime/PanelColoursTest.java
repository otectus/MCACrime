package dev.otectus.mcacrime;

import dev.otectus.mcacrime.action.ActionLegality;
import dev.otectus.mcacrime.client.NameColors;
import dev.otectus.mcacrime.client.screen.PanelColours;
import dev.otectus.mcacrime.ledger.Resolution;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A tripwire against the quietest bug a light GUI can ship: information rendered in a colour nobody
 * can read.
 *
 * <p>Moving the screens from a dark violet panel to vanilla's grey one inverted the problem every
 * colour in this mod was chosen for. {@code 0x55FF55} on dark violet is a clear green; the same
 * green on {@code #C6C6C6} is very nearly the panel itself. Nothing crashes, no test fails on its
 * own, and the player simply stops being told which actions are lawful — on a monitor, or at a
 * brightness, or with a colour vision that differs from the author's.
 *
 * <p>So every colour the mod draws as text on the panel is measured here, not eyeballed.
 */
class PanelColoursTest {

    @Test
    void everyLegalityColourIsReadableOnThePanel() {
        for (ActionLegality legality : ActionLegality.values()) {
            int onPanel = PanelColours.onPanel(legality.rgb());
            assertTrue(PanelColours.contrast(onPanel, PanelColours.FACE) >= PanelColours.MIN_CONTRAST,
                    legality + " is not legible as text on the panel");
        }
    }

    @Test
    void everyResolutionColourIsReadableOnThePanel() {
        for (Resolution resolution : Resolution.values()) {
            int onPanel = PanelColours.onPanel(PanelColours.resolution(resolution));
            assertTrue(PanelColours.contrast(onPanel, PanelColours.FACE) >= PanelColours.MIN_CONTRAST,
                    resolution + " is not legible as text on the panel");
        }
    }

    @Test
    void everyBandColourIsReadableOnThePanel() {
        for (int rgb : new int[] {NameColors.BLUE_RGB, NameColors.RED_RGB, NameColors.GREY_RGB}) {
            int onPanel = PanelColours.onPanel(rgb);
            assertTrue(PanelColours.contrast(onPanel, PanelColours.FACE) >= PanelColours.MIN_CONTRAST,
                    Integer.toHexString(rgb) + " is not legible as text on the panel");
        }
    }

    @Test
    void bodyTextIsReadableAndTheOtherTiersStayInOrder() {
        double text = PanelColours.contrast(PanelColours.TEXT, PanelColours.FACE);
        double muted = PanelColours.contrast(PanelColours.TEXT_MUTED, PanelColours.FACE);
        double disabled = PanelColours.contrast(PanelColours.TEXT_DISABLED, PanelColours.FACE);

        assertTrue(text >= PanelColours.MIN_CONTRAST, "body text is not legible on the panel");

        // Muted text is deliberately quieter than body text, but it is still text a player is
        // expected to read — a countdown, a fine, a target's name.
        assertTrue(muted >= 3.0D, "muted text is too faint to read");

        // Disabled text must read as switched off rather than as absent. A row the server refused
        // is still information: the mod shows blocked actions on purpose instead of hiding them.
        assertTrue(disabled >= 1.8D, "disabled text has faded into the panel");

        assertTrue(text > muted && muted > disabled,
                "the three text tiers must be distinguishable from each other, brightest first");
    }

    @Test
    void darkeningPreservesHueAndKeepsColoursApart() {
        // Whatever onPanel does to reach its contrast target, green must still be greener than it
        // is red, or the marker stops meaning anything.
        int lawful = PanelColours.onPanel(ActionLegality.LAWFUL.rgb());
        int criminal = PanelColours.onPanel(ActionLegality.CRIMINAL.rgb());

        assertTrue(green(lawful) > red(lawful), "lawful stopped reading as green");
        assertTrue(red(criminal) > green(criminal), "criminal stopped reading as red");

        // And no two legalities may collapse onto the same colour on the way down.
        for (ActionLegality a : ActionLegality.values()) {
            for (ActionLegality b : ActionLegality.values()) {
                if (a != b) {
                    assertNotEquals(PanelColours.onPanel(a.rgb()), PanelColours.onPanel(b.rgb()),
                            a + " and " + b + " darken to the same colour");
                }
            }
        }
    }

    @Test
    void aColourAlreadyDarkEnoughIsLeftAlone() {
        // Bisection must not darken something that already passes: an almost-black colour that
        // came back blacker would be a sign the search had inverted.
        int nearBlack = 0x101010;
        assertEquals(nearBlack, PanelColours.onPanel(nearBlack));
    }

    private static int red(int rgb) {
        return (rgb >> 16) & 0xFF;
    }

    private static int green(int rgb) {
        return (rgb >> 8) & 0xFF;
    }
}
