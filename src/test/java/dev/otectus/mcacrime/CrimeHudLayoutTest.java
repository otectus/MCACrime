package dev.otectus.mcacrime;

import dev.otectus.mcacrime.client.hud.CrimeHudLayout;
import dev.otectus.mcacrime.client.hud.HudAnchor;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CrimeHudLayoutTest {
    @Test void heatAndSentenceFitBelowChatAndBesideHotbarAtEveryGuiScale() {
        for (int width : new int[] {320, 427, 640, 854, 1920}) {
            for (int height : new int[] {180, 240, 360, 480, 1080}) {
                for (int textWidth : new int[] {96, 128, 220}) {
                    var p = CrimeHudLayout.place(HudAnchor.BOTTOM_LEFT, width, height, textWidth, 26, 4, 4);
                    assertTrue(p.x() >= 2);
                    assertTrue(p.x() + Math.ceil(textWidth * p.scale()) <= width / 2 - 94);
                    assertTrue(p.y() >= height - 30, "panel overlaps chat or its queued-message strip");
                    assertTrue(p.y() + Math.ceil(26 * p.scale()) <= height - 4);
                    assertTrue(p.scale() > 0 && p.scale() <= 1);
                }
            }
        }
    }

    @Test void sentenceAloneKeepsTheSameBottomEdgeWithoutAnEmptyHeatSlot() {
        var both = CrimeHudLayout.place(HudAnchor.BOTTOM_LEFT, 640, 360, 120, 26, 4, 4);
        var sentence = CrimeHudLayout.place(HudAnchor.BOTTOM_LEFT, 640, 360, 120, 16, 4, 4);
        assertEquals(both.y() + 26, sentence.y() + 16);
        assertEquals(340, sentence.y());
    }

    @Test void formerTopLeftDefaultMigratesButCustomPlacementsSurvive() {
        assertEquals(HudAnchor.BOTTOM_LEFT, CrimeHudLayout.migratedAnchor(HudAnchor.TOP_LEFT, 4, 4));
        assertEquals(HudAnchor.TOP_LEFT, CrimeHudLayout.migratedAnchor(HudAnchor.TOP_LEFT, 20, 4));
        assertEquals(HudAnchor.TOP_RIGHT, CrimeHudLayout.migratedAnchor(HudAnchor.TOP_RIGHT, 4, 4));
    }

    @Test void oldOffsetsCannotPushTheBottomDockIntoChatOrOffscreen() {
        for (int offset : new int[] {-4096, 0, 4, 200, 4096}) {
            var p = CrimeHudLayout.place(HudAnchor.BOTTOM_LEFT, 427, 240, 120, 26, offset, offset);
            assertTrue(p.y() >= 210);
            assertTrue(p.x() + Math.ceil(120 * p.scale()) <= 119);
            assertTrue(p.y() + Math.ceil(26 * p.scale()) <= 238);
        }
    }
}
