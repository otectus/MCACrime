package dev.otectus.mcacrime;

import dev.otectus.mcacrime.crime.type.CrimeType;
import dev.otectus.mcacrime.detect.CrimeDetector;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The witnessed/unwitnessed Karma/Heat split (spec §3.5), as pure functions of a crime type. */
class CrimeApplicationTest {

    private static CrimeType type(double witnessedMultiplier) {
        return new CrimeType(new ResourceLocation("mcacrime", "harm_villager"), -10L, 20L, witnessedMultiplier, "villager");
    }

    @Test
    void witnessedAppliesFullKarmaAndHeatTimesMultiplier() {
        CrimeType t = type(2.0);
        assertEquals(-20L, CrimeDetector.karmaFor(t, true, 1.0));   // round(-10 * 2.0)
        assertEquals(40L, CrimeDetector.heatFor(t, true, true));    // round(20 * 2.0)
    }

    @Test
    void unwitnessedScalesKarmaByFactorAndGivesNoHeatByDefault() {
        CrimeType t = type(1.0);
        assertEquals(-10L, CrimeDetector.karmaFor(t, false, 1.0));  // full karma at factor 1.0
        assertEquals(-5L, CrimeDetector.karmaFor(t, false, 0.5));   // round(-10 * 0.5)
        assertEquals(0L, CrimeDetector.heatFor(t, false, true));    // requireWitnessForHeat -> no heat
    }

    @Test
    void unwitnessedHeatAppliesWhenWitnessNotRequired() {
        CrimeType t = type(1.0);
        assertEquals(20L, CrimeDetector.heatFor(t, false, false));  // raw heatDelta, no multiplier
    }
}
