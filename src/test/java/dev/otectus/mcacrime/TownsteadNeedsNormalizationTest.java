package dev.otectus.mcacrime;

import dev.otectus.mcacrime.compat.TownsteadNeeds;
import dev.otectus.mcacrime.compat.TownsteadNeedsView;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Three scales with three different maxima, and the one zero that must never mean "starving".
 *
 * <p>Hunger runs 0–100, thirst 0–20 and fatigue 0–20 upwards to collapse. Getting any of those wrong
 * is not a visible bug — it is a village that reacts slightly wrong for the rest of the save — so the
 * ranges are pinned here rather than left to the call sites.
 */
class TownsteadNeedsNormalizationTest {

    private static TownsteadNeedsView tracked(int hunger, int thirst, int fatigue) {
        return new TownsteadNeedsView(true, hunger, 0f, 0f, thirst, 0, 0f, fatigue, false, false);
    }

    @Test
    void eachScaleNormalisesAgainstItsOwnMaximum() {
        assertEquals(1.0D, TownsteadNeeds.hunger(tracked(100, 20, 0)), 1e-6, "hunger is out of 100");
        assertEquals(0.5D, TownsteadNeeds.hunger(tracked(50, 20, 0)), 1e-6);
        assertEquals(0.0D, TownsteadNeeds.hunger(tracked(0, 20, 0)), 1e-6);

        assertEquals(1.0D, TownsteadNeeds.thirst(tracked(100, 20, 0)), 1e-6, "thirst is out of 20");
        assertEquals(0.5D, TownsteadNeeds.thirst(tracked(100, 10, 0)), 1e-6);
        assertEquals(0.0D, TownsteadNeeds.thirst(tracked(100, 0, 0)), 1e-6);
    }

    @Test
    void fatigueIsInvertedSoOneAlwaysMeansFine() {
        assertEquals(1.0D, TownsteadNeeds.rest(tracked(100, 20, 0)), 1e-6, "no fatigue is fully rested");
        assertEquals(0.5D, TownsteadNeeds.rest(tracked(100, 20, 10)), 1e-6);
        assertEquals(0.0D, TownsteadNeeds.rest(tracked(100, 20, 20)), 1e-6, "20 is the collapse point");
    }

    @Test
    void anUntrackedReadingIsNeverStarvingAndNeverAdjustsAnything() {
        TownsteadNeedsView none = TownsteadNeedsView.untracked();

        assertFalse(none.starving(), "no Townstead is not a village on the edge of death");
        assertFalse(none.parched());
        assertFalse(none.exhausted());
        assertFalse(none.incapacitated());

        assertEquals(1.0D, TownsteadNeeds.hunger(none), 1e-6);
        assertEquals(1.0D, TownsteadNeeds.thirst(none), 1e-6);
        assertEquals(1.0D, TownsteadNeeds.rest(none), 1e-6);
        assertEquals(0.0D, TownsteadNeeds.threatAdjustment(none), 1e-6);
        assertEquals(0.0D, TownsteadNeeds.threatAdjustment(null), 1e-6);
        assertEquals(0.62D, TownsteadNeeds.adjustFactor(0.62D, none, true), 1e-6,
                "an untracked reading contributes exactly zero even with the switch on");
    }

    @Test
    void aTrackedZeroIsStarvingAndATrackedFullIsNot() {
        assertTrue(tracked(0, 20, 0).starving());
        assertTrue(tracked(100, 0, 0).parched());
        assertTrue(tracked(100, 20, 20).exhausted());
        assertFalse(tracked(100, 20, 0).starving());
    }

    @Test
    void theThreatAdjustmentIsCappedBothWays() {
        double worst = TownsteadNeeds.threatAdjustment(tracked(0, 0, 20));
        double best = TownsteadNeeds.threatAdjustment(tracked(100, 20, 0));

        assertEquals(-TownsteadNeeds.MAX_THREAT_ADJUSTMENT, worst, 1e-6);
        assertEquals(TownsteadNeeds.MAX_THREAT_ADJUSTMENT, best, 1e-6);
        assertEquals(0.0D, TownsteadNeeds.threatAdjustment(tracked(50, 10, 10)), 1e-6,
                "the middle of the scale is the state MCA: Crime's own numbers were tuned at");

        for (int hunger = 0; hunger <= 100; hunger += 5) {
            for (int fatigue = 0; fatigue <= 20; fatigue += 5) {
                double adjustment = TownsteadNeeds.threatAdjustment(tracked(hunger, 20 - fatigue, fatigue));
                assertTrue(Math.abs(adjustment) <= TownsteadNeeds.MAX_THREAT_ADJUSTMENT + 1e-9,
                        "hunger " + hunger + " fatigue " + fatigue + " -> " + adjustment);
            }
        }
    }

    @Test
    void theSwitchIsWhatDecidesWhetherAnythingMoves() {
        TownsteadNeedsView starving = tracked(0, 0, 20);

        assertEquals(0.60D, TownsteadNeeds.adjustFactor(0.60D, starving, false), 1e-6,
                "off by default means the factor a server owner tuned, untouched");
        assertEquals(0.50D, TownsteadNeeds.adjustFactor(0.60D, starving, true), 1e-6);
    }

    @Test
    void anAdjustedFactorStaysInsideItsOwnRange() {
        assertEquals(0.0D, TownsteadNeeds.adjustFactor(0.02D, tracked(0, 0, 20), true), 1e-6);
        assertEquals(1.0D, TownsteadNeeds.adjustFactor(0.98D, tracked(100, 20, 0), true), 1e-6);
    }
}
