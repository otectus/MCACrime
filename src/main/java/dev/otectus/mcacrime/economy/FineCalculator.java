package dev.otectus.mcacrime.economy;

import dev.otectus.mcacrime.crime.Band;

import java.util.OptionalLong;

/**
 * Pure fine math (spec §6.1). Returns the emerald cost to clear current Heat, or empty when the offender
 * is <b>not finable</b> (Heat at/above the jailable threshold → must serve jail/surrender; or a Red player
 * who must surrender first). Unit-testable — no game/config deps.
 */
public final class FineCalculator {

    private FineCalculator() {
    }

    public static OptionalLong fineFor(long heat, Band band, int fineBase, int finePerHeat,
                                       int jailableHeatThreshold, double blueFineMultiplier, boolean redCanPayFine) {
        if (heat <= 0L) {
            return OptionalLong.of(0L); // not Wanted / nothing owed
        }
        if (heat >= jailableHeatThreshold) {
            return OptionalLong.empty(); // too severe to fine away — jail or surrender
        }
        if (band == Band.RED && !redCanPayFine) {
            return OptionalLong.empty(); // outlaws must surrender before paying
        }
        long base = fineBase + heat * finePerHeat;
        if (band == Band.BLUE) {
            base = Math.round(base * blueFineMultiplier);
        }
        return OptionalLong.of(Math.max(0L, base));
    }
}
