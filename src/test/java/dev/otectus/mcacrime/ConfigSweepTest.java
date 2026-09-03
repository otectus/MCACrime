package dev.otectus.mcacrime;

import dev.otectus.mcacrime.McaCrimeConfig.ProfessionMatchingMode;
import dev.otectus.mcacrime.action.handler.BailActionHandler;
import dev.otectus.mcacrime.compat.ProfessionMatcher;
import dev.otectus.mcacrime.crime.Band;
import dev.otectus.mcacrime.enforcement.ChallengeResponse;
import dev.otectus.mcacrime.enforcement.GuardChallenge;
import dev.otectus.mcacrime.jail.JailState;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The settings that used to be declared and never read.
 *
 * <p>Each block below covers one key from the plan's §22.3 audit that shipped in the TOML, was
 * validated by {@code /crime validate}, and was consulted by no code at all. The pure halves are
 * asserted here; the wiring itself is what the in-world checklist covers.
 */
class ConfigSweepTest {

    // ------------------------------------------------------------------ professionMatchingMode

    private static final ResourceLocation MCA_GUARD = ResourceLocation.fromNamespaceAndPath("mca", "guard");
    private static final ResourceLocation MODDED_GUARD = ResourceLocation.fromNamespaceAndPath("somemod", "guard");
    private static final ResourceLocation CAPTAIN = ResourceLocation.fromNamespaceAndPath("mca", "guard_captain");

    @Test
    void strictMatchingCaresAboutTheWholeId() {
        assertTrue(ProfessionMatcher.matches(MCA_GUARD, "guard", ProfessionMatchingMode.STRICT));
        assertTrue(ProfessionMatcher.matches(MODDED_GUARD, "guard", ProfessionMatchingMode.STRICT));
        assertFalse(ProfessionMatcher.matches(CAPTAIN, "guard", ProfessionMatchingMode.STRICT));
    }

    @Test
    void normalisedMatchingIgnoresTheNamespaceOnly() {
        // This is the historical behaviour and stays the default: a modded guard counts, a captain does not.
        assertTrue(ProfessionMatcher.matches(MODDED_GUARD, "guard", ProfessionMatchingMode.NORMALIZED));
        assertFalse(ProfessionMatcher.matches(CAPTAIN, "guard", ProfessionMatchingMode.NORMALIZED));
    }

    @Test
    void looseMatchingCatchesCompoundNames() {
        assertTrue(ProfessionMatcher.matches(CAPTAIN, "guard", ProfessionMatchingMode.LOOSE));
        assertTrue(ProfessionMatcher.matches(ResourceLocation.fromNamespaceAndPath("mca", "village_guard"), "guard",
                ProfessionMatchingMode.LOOSE));
        assertFalse(ProfessionMatcher.matches(ResourceLocation.fromNamespaceAndPath("mca", "farmer"), "guard",
                ProfessionMatchingMode.LOOSE));
    }

    @Test
    void matchingIsNullSafeInEveryDirection() {
        assertFalse(ProfessionMatcher.matches(null, "guard", ProfessionMatchingMode.LOOSE));
        assertFalse(ProfessionMatcher.matches(MCA_GUARD, null, ProfessionMatchingMode.LOOSE));
        assertFalse(ProfessionMatcher.matches(MCA_GUARD, "", ProfessionMatchingMode.LOOSE));
        assertFalse(ProfessionMatcher.matches(MCA_GUARD, "guard", null));
    }

    // ------------------------------------------------------------------ enableBail

    private static JailState sentence(long remaining, long served) {
        JailState jail = new JailState(remaining, new BlockPos(0, 64, 0),
                ResourceLocation.fromNamespaceAndPath("minecraft", "overworld"), 8,
                dev.otectus.mcacrime.jail.JailContainmentMode.CONTAINMENT);
        jail.setRealOnlineTicksServed(served);
        return jail;
    }

    @Test
    void servedFractionMovesFromNothingToEverything() {
        assertEquals(0.0D, BailActionHandler.servedFraction(sentence(1000L, 0L)), 0.0001);
        assertEquals(0.5D, BailActionHandler.servedFraction(sentence(500L, 500L)), 0.0001);
        assertEquals(1.0D, BailActionHandler.servedFraction(sentence(0L, 1000L)), 0.0001);
    }

    @Test
    void aSentenceWithNoLengthReadsAsServedRatherThanTrapped() {
        // The failure direction matters: being allowed to pay is recoverable, being unable to is not.
        assertEquals(1.0D, BailActionHandler.servedFraction(sentence(0L, 0L)), 0.0001);
    }

    @Test
    void bailIsPricedOnWhatIsLeftAndRoundsUp() {
        // 1200 ticks is one minute exactly; 1201 is still two minutes' worth, never one and a bit free.
        assertEquals(4L, BailActionHandler.costFor(1200L, 4));
        assertEquals(8L, BailActionHandler.costFor(1201L, 4), "a sliver of a minute must not be free");
        assertEquals(0L, BailActionHandler.costFor(0L, 4),
                "a finished sentence costs nothing to buy out");
        assertEquals(0L, BailActionHandler.costFor(-5000L, 4), "a negative remainder is not a refund");
    }

    // ------------------------------------------------------------------ guard challenge

    private static GuardChallenge challenge(int charges, long fine, boolean finable) {
        return new GuardChallenge(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                null, charges, fine, finable, 0L, 200L);
    }

    @Test
    void payingIsOfferedOnlyWhenItWouldActuallySettleSomething() {
        assertTrue(challenge(2, 12L, true).canPay());
        assertFalse(challenge(2, 12L, false).canPay(), "not every charge can be paid off");
        assertFalse(challenge(2, 0L, true).canPay(), "a zero fine is not a payment option");
        assertFalse(challenge(0, 12L, true).canPay(), "nothing to answer for is not a payment option");
    }

    @Test
    void theCountdownNeverGoesNegative() {
        GuardChallenge open = challenge(1, 5L, true);
        assertEquals(200L, open.remaining(0L));
        assertEquals(0L, open.remaining(500L));
        assertTrue(open.expired(200L));
    }

    @Test
    void askingForTheChargesDoesNotEndTheEncounter() {
        // The one response that has to leave the window open: the point of asking is to then decide.
        assertFalse(ChallengeResponse.ASK_CHARGES.closesEncounter());
        assertTrue(ChallengeResponse.SURRENDER.closesEncounter());
        assertTrue(ChallengeResponse.PAY_FINE.closesEncounter());
        assertTrue(ChallengeResponse.REFUSE.closesEncounter());
    }

    @Test
    void anOutOfRangeResponseDecodesAsRefusal() {
        // A hostile or older client must not be able to pick a better outcome than not answering.
        assertEquals(ChallengeResponse.REFUSE, ChallengeResponse.byOrdinal(-1));
        assertEquals(ChallengeResponse.REFUSE, ChallengeResponse.byOrdinal(99));
        assertEquals(ChallengeResponse.SURRENDER, ChallengeResponse.byOrdinal(0));
    }

    // ------------------------------------------------------------------ validator

    /** A configuration with nothing wrong with it. */
    private static java.util.List<String> validate(boolean observations, boolean reactions, boolean dialogue,
                                                   int sight, int hearing, int report, boolean bail,
                                                   int bailCost, boolean npcCrime, boolean rescue,
                                                   boolean kidnapNpc) {
        return dev.otectus.mcacrime.config.ConfigValidator.validateBehaviour(observations, reactions,
                dialogue, sight, hearing, report, bail, bailCost, npcCrime, rescue, kidnapNpc);
    }

    @Test
    void theShippedDefaultsValidateClean() {
        assertTrue(validate(true, true, true, 12, 16, 24, false, 4, false, true, true).isEmpty(),
                "the out-of-the-box configuration must not warn about itself");
    }

    @Test
    void reactionsWithoutObservationsIsFlagged() {
        // The combination that silently disables the headline feature: reactions are started by
        // observations, so this config parses fine and produces a village that never reacts.
        assertEquals(1, validate(false, true, true, 12, 16, 24, false, 4, false, true, true).size());
    }

    @Test
    void aHearingRadiusInsideTheSightRadiusIsFlagged() {
        assertEquals(1, validate(true, true, true, 20, 8, 24, false, 4, false, true, true).size(),
                "anyone close enough to hear it can already see it, so the role becomes unreachable");
    }

    @Test
    void freeBailIsFlagged() {
        assertEquals(1, validate(true, true, true, 12, 16, 24, true, 0, false, true, true).size());
        assertTrue(validate(true, true, true, 12, 16, 24, true, 4, false, true, true).isEmpty());
    }

    @Test
    void enablingTheUnimplementedNpcCrimeSeamIsFlagged() {
        assertEquals(1, validate(true, true, true, 12, 16, 24, false, 4, true, true, true).size(),
                "a skeleton switch that changes nothing must say so rather than look supported");
    }

    // ------------------------------------------------------------------ allowKillingRed

    @Test
    void redAloneIsALegalTargetOnlyWhenConfigured() {
        assertFalse(dev.otectus.mcacrime.enforcement.LegalTarget.isLegalTarget(
                false, Band.RED, false, false, false));
        assertTrue(dev.otectus.mcacrime.enforcement.LegalTarget.isLegalTarget(
                false, Band.RED, true, false, false));
        // Wanted, escaped, and holding a captive are each independently sufficient regardless of band.
        assertTrue(dev.otectus.mcacrime.enforcement.LegalTarget.isLegalTarget(
                true, Band.BLUE, false, false, false));
        assertTrue(dev.otectus.mcacrime.enforcement.LegalTarget.isLegalTarget(
                false, Band.BLUE, false, true, false));
        assertTrue(dev.otectus.mcacrime.enforcement.LegalTarget.isLegalTarget(
                false, Band.BLUE, false, false, true));
    }
}
