package dev.otectus.mcacrime;

import dev.otectus.mcacrime.client.hud.HudAnchor;
import dev.otectus.mcacrime.util.TickFormat;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * HUD placement arithmetic and duration formatting.
 *
 * <p>Neither of these can crash, which is exactly why they need tests: a mispositioned overlay is an
 * element drawn half off the edge on somebody else's monitor at a GUI scale you never tried, and a
 * mis-rounded clock is a sentence that reads {@code 0:00} while the player is still serving it.
 */
class HudAnchorTest {

    // A 1080p screen at GUI scale 3 gives roughly this much virtual space.
    private static final int W = 640;
    private static final int H = 360;

    @Test
    void cornersLandInTheirCorners() {
        assertEquals(0, HudAnchor.TOP_LEFT.x(W, 100, 0));
        assertEquals(0, HudAnchor.TOP_LEFT.y(H, 20, 0));

        assertEquals(W - 100, HudAnchor.TOP_RIGHT.x(W, 100, 0));
        assertEquals(0, HudAnchor.TOP_RIGHT.y(H, 20, 0));

        // Bottom anchors stop short of the edge by the hotbar clearance rather than sitting flush
        // against it, which would draw the element behind vanilla's own HUD.
        assertEquals(0, HudAnchor.BOTTOM_LEFT.x(W, 100, 0));
        assertEquals(H - 20 - 52, HudAnchor.BOTTOM_LEFT.y(H, 20, 0));

        assertEquals(W - 100, HudAnchor.BOTTOM_RIGHT.x(W, 100, 0));
        assertEquals(H - 20 - 52, HudAnchor.BOTTOM_RIGHT.y(H, 20, 0));
    }

    @Test
    void bottomAnchorsClearTheVanillaHudBand() {
        // The armor row is the topmost thing in vanilla's bottom band, at height - 49. A player who
        // picks a bottom corner must not have to discover their Heat plate is hidden behind it.
        for (HudAnchor anchor : HudAnchor.values()) {
            if (!anchor.isBottom()) continue;
            int y = anchor.y(H, 24, 4);
            assertTrue(y + 24 <= H - 49, anchor + " overlapped the armor row");
        }
        // Bottom centre must additionally clear this mod's own channel bar label at height - 72.
        assertTrue(HudAnchor.BOTTOM_CENTER.y(H, 24, 4) + 24 <= H - 72,
                "BOTTOM_CENTER overlapped the channel bar");
    }

    @Test
    void aPositiveOffsetMovesEveryAnchorInward() {
        // One pair of offset defaults has to read sensibly at all eight anchors, so "4" must mean a
        // four-pixel gap from the anchored edge rather than four pixels down-and-right of wherever.
        for (HudAnchor anchor : HudAnchor.values()) {
            int x0 = anchor.x(W, 100, 0);
            int x8 = anchor.x(W, 100, 8);
            int y0 = anchor.y(H, 20, 0);
            int y8 = anchor.y(H, 20, 8);
            assertEquals(x0 + (anchor.name().endsWith("RIGHT") ? -8 : 8), x8,
                    anchor + " did not move inward horizontally");
            assertEquals(y0 + (anchor.isBottom() ? -8 : 8), y8,
                    anchor + " did not move inward vertically");
        }
    }

    @Test
    void centredAnchorsCentre() {
        assertEquals((W - 100) / 2, HudAnchor.TOP_CENTER.x(W, 100, 0));
        assertEquals((H - 20) / 2, HudAnchor.CENTER_LEFT.y(H, 20, 0));
    }

    @Test
    void offsetsNudgeButNeverPushOffScreen() {
        assertEquals(10, HudAnchor.TOP_LEFT.x(W, 100, 10));

        // A player who set a big offset on a wide monitor and then opened the game on a laptop should
        // find the element moved, not gone.
        assertEquals(W - 100, HudAnchor.TOP_LEFT.x(W, 100, 100_000));
        assertEquals(H - 20, HudAnchor.TOP_LEFT.y(H, 20, 100_000));

        // On a right or bottom anchor the offset counts the other way, so the clamps mirror: a huge
        // positive offset drives the element inward until it hits the far edge, a huge negative one
        // pushes it back out to the edge it is anchored to.
        assertEquals(0, HudAnchor.TOP_RIGHT.x(W, 100, 100_000));
        assertEquals(W - 100, HudAnchor.TOP_RIGHT.x(W, 100, -100_000));
        assertEquals(0, HudAnchor.BOTTOM_LEFT.y(H, 20, 100_000));
        assertEquals(H - 20, HudAnchor.BOTTOM_LEFT.y(H, 20, -100_000));
    }

    @Test
    void anElementLargerThanTheScreenPinsToZero() {
        // No valid position exists, so it must not produce a negative coordinate that draws it
        // entirely off the top-left instead of merely overflowing.
        for (HudAnchor anchor : HudAnchor.values()) {
            assertEquals(0, anchor.x(100, 400, 0), anchor + " produced a negative x");
            assertEquals(0, anchor.y(100, 400, 0), anchor + " produced a negative y");
        }
    }

    @Test
    void everyAnchorStaysOnScreenAtEveryScale() {
        // 4 is Minecraft's largest GUI scale; the virtual screen gets small enough that clamping
        // stops being theoretical.
        for (int width : new int[] {320, 427, 640, 854, 1920}) {
            for (int height : new int[] {180, 240, 360, 480, 1080}) {
                for (HudAnchor anchor : HudAnchor.values()) {
                    int x = anchor.x(width, 120, 8);
                    int y = anchor.y(height, 24, 8);
                    assertTrue(x >= 0 && x + 120 <= width,
                            anchor + " left the screen horizontally at " + width + "x" + height);
                    assertTrue(y >= 0 && y + 24 <= height,
                            anchor + " left the screen vertically at " + width + "x" + height);
                }
            }
        }
    }

    @Test
    void bottomAnchorsKnowTheyAreAtTheBottom() {
        // Bottom anchors count both their offset and their hotbar clearance upward from the edge;
        // getting this backwards puts the element behind the hotbar.
        assertTrue(HudAnchor.BOTTOM_LEFT.isBottom());
        assertTrue(HudAnchor.BOTTOM_CENTER.isBottom());
        assertTrue(HudAnchor.BOTTOM_RIGHT.isBottom());
        assertFalse(HudAnchor.TOP_LEFT.isBottom());
        assertFalse(HudAnchor.CENTER_LEFT.isBottom());
    }

    @Test
    void durationsReadAsClocks() {
        assertEquals("0:00", TickFormat.clock(0L));
        assertEquals("0:01", TickFormat.clock(20L));
        assertEquals("1:00", TickFormat.clock(1200L));
        assertEquals("6:00", TickFormat.clock(7200L));
        assertEquals("1:00:00", TickFormat.clock(72000L));
        assertEquals("2:03:04", TickFormat.clock((2 * 3600 + 3 * 60 + 4) * 20L));
    }

    @Test
    void aPartialSecondRoundsUpSoATimerNeverReadsZeroEarly() {
        assertEquals("0:01", TickFormat.clock(1L),
                "A sentence with one tick left is not served yet and must not display as 0:00");
        assertEquals(1L, TickFormat.seconds(1L));
        assertEquals(1L, TickFormat.seconds(20L));
        assertEquals(2L, TickFormat.seconds(21L));
    }

    @Test
    void negativeDurationsAreTreatedAsNothingLeft() {
        assertEquals("0:00", TickFormat.clock(-1L));
        assertEquals(0L, TickFormat.seconds(-500L));
    }
}
