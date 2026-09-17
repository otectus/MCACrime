package dev.otectus.mcacrime;

import dev.otectus.mcacrime.economy.EconomyProfile;
import dev.otectus.mcacrime.economy.EconomyProfileResolver;
import dev.otectus.mcacrime.economy.account.VillagerPurse;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The optional economy, and the one line in reference §12.2 that is not a matter of taste: no profile
 * may mint currency.
 *
 * <p>Everything else here is a bound. A settlement can make a fine half again as large or a third
 * smaller, and that is a tuning decision; a settlement that could raise a villager's daily income
 * above the configured one would be printing emeralds from a building count, which §12.2 rules out in
 * as many words. So {@link EconomyProfile#scaleRefill} is asymmetric on purpose and is asserted
 * against every profile rather than against the one that happens to be above 1 today.
 */
class EconomyProfileTest {

    @Test
    void everyMultiplierStaysInsideTheDocumentedBand() {
        for (EconomyProfile profile : EconomyProfile.values()) {
            assertTrue(profile.fineScale() >= EconomyProfile.MIN_MULTIPLIER
                    && profile.fineScale() <= EconomyProfile.MAX_MULTIPLIER,
                    profile.id() + " fine scale is outside [0.5, 2.0]");
            assertTrue(profile.bountyScale() >= EconomyProfile.MIN_MULTIPLIER
                    && profile.bountyScale() <= EconomyProfile.MAX_MULTIPLIER,
                    profile.id() + " bounty scale is outside [0.5, 2.0]");
            assertTrue(profile.fenceScale() >= EconomyProfile.MIN_MULTIPLIER
                    && profile.fenceScale() <= EconomyProfile.MAX_MULTIPLIER,
                    profile.id() + " fence scale is outside [0.5, 2.0]");
            assertTrue(profile.refillScale() >= EconomyProfile.MIN_MULTIPLIER
                    && profile.refillScale() <= EconomyProfile.MAX_MULTIPLIER,
                    profile.id() + " refill scale is outside [0.5, 2.0]");
        }
    }

    @Test
    void townChangesNothingAtAll() {
        assertTrue(EconomyProfile.TOWN.neutral(),
                "TOWN is what every settlement is when nothing can be read, so it has to be identity");
        assertEquals(100, EconomyProfile.TOWN.scaleFine(100));
        assertEquals(100L, EconomyProfile.TOWN.scaleBounty(100L));
        assertEquals(100L, EconomyProfile.TOWN.scalePrice(100L));
        assertEquals(7, EconomyProfile.TOWN.scaleRefill(7));
    }

    @Test
    void noProfileCanRaiseAVillagersConfiguredIncome() {
        for (EconomyProfile profile : EconomyProfile.values()) {
            for (int configured : new int[] { 0, 1, 3, 9, 64, Integer.MAX_VALUE }) {
                assertTrue(profile.scaleRefill(configured) <= configured,
                        profile.id() + " raised a purse refill above the configured " + configured
                                + ", which is exactly the minting reference §12.2 forbids");
                assertTrue(profile.scaleRefill(configured) >= 0);
            }
        }
    }

    @Test
    void aScaledRefillStillCannotPushAPurseAboveItsCapacity() {
        VillagerPurse purse = new VillagerPurse(9, 10, 8, 1L);

        purse.refill(2L, EconomyProfile.PROSPEROUS.scaleRefill(8));

        assertEquals(10, purse.balance(), "capacity bounds the refill however the profile scaled it");
    }

    @Test
    void aPoorSettlementRefillsLessAndNeverMore() {
        VillagerPurse frontier = new VillagerPurse(0, 100, 10, 1L);
        VillagerPurse town = new VillagerPurse(0, 100, 10, 1L);

        frontier.refill(2L, EconomyProfile.FRONTIER.scaleRefill(10));
        town.refill(2L, EconomyProfile.TOWN.scaleRefill(10));

        assertTrue(frontier.balance() < town.balance());
        assertEquals(10, town.balance(), "an ordinary town pays exactly what the config says");
    }

    @Test
    void theCappedRefillStillOnlyAppliesOncePerDay() {
        VillagerPurse purse = new VillagerPurse(0, 100, 10, 1L);

        purse.refill(2L, 5);
        purse.refill(2L, 5);

        assertEquals(5, purse.balance(),
                "the day stamp is what bounds a refill; capping the income must not have unbounded it");
    }

    @Test
    void negativeAndZeroValuesScaleToNothingRatherThanToDebt() {
        for (EconomyProfile profile : EconomyProfile.values()) {
            assertEquals(0, profile.scaleFine(0));
            assertEquals(0L, profile.scaleBounty(-50L));
            assertEquals(0L, profile.scalePrice(-1L));
            assertEquals(0, profile.scaleRefill(-3));
        }
    }

    @Test
    void theRuleThatPicksAProfileIsPureAndDefaultsToTown() {
        assertEquals(EconomyProfile.PROSPEROUS, EconomyProfileResolver.select(
                EconomyProfileResolver.PROSPEROUS_BUILDINGS, 0));
        assertEquals(EconomyProfile.PROSPEROUS, EconomyProfileResolver.select(
                1, EconomyProfileResolver.PROSPEROUS_SPIRIT));
        assertEquals(EconomyProfile.FRONTIER, EconomyProfileResolver.select(2, 1));
        assertEquals(EconomyProfile.TOWN, EconomyProfileResolver.select(
                EconomyProfileResolver.FRONTIER_BUILDINGS, 1));
        assertEquals(EconomyProfile.TOWN, EconomyProfileResolver.select(0, 0),
                "no reading is not an empty village; a settlement nothing could be read about has to "
                        + "be priced exactly as it was before this feature existed");
    }

    @Test
    void theResolverIsInertWithNoConfigOrNoServer() {
        assertFalse(EconomyProfileResolver.enabled(),
                "an unloaded config must read as off, not throw into a trade screen");
        assertEquals(EconomyProfile.TOWN, EconomyProfileResolver.of(null));
        assertEquals(EconomyProfile.TOWN, EconomyProfileResolver.of(null, null));
        assertEquals(0, EconomyProfileResolver.cachedCount(),
                "nothing is cached while the feature is off");
    }

    @Test
    void anIdParsesBackToItsProfileAndNothingElseDoes() {
        for (EconomyProfile profile : EconomyProfile.values()) {
            assertEquals(profile, EconomyProfile.parse(profile.id()).orElse(null));
            assertEquals(profile, EconomyProfile.parse(profile.name()).orElse(null));
        }
        assertTrue(EconomyProfile.parse("metropolis").isEmpty());
        assertTrue(EconomyProfile.parse(null).isEmpty());
    }
}
