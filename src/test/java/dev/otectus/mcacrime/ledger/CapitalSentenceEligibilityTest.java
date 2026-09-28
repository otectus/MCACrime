package dev.otectus.mcacrime.ledger;

import dev.otectus.mcacrime.crime.type.CrimeIds;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.OptionalInt;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * One offence, and no other path to a death sentence (0.7.5 §3.19, acceptance 21.6).
 *
 * <p>Every assertion here is a way somebody could otherwise be sentenced to death by accident, and
 * they are asserted against the pure overload of {@link CapitalSentenceService#qualifies} so that
 * the configuration is an argument rather than an ambient fact. That matters more than usual: "no
 * other offence ever escalates to it" is a claim about <em>everything else</em>, and a test that
 * could only check one configuration would be checking almost nothing.
 */
class CapitalSentenceEligibilityTest {

    private static final UUID OFFENDER = UUID.randomUUID();

    private static CrimeRecord caseOf(ResourceLocation type, Resolution resolution) {
        return new CrimeRecord(UUID.randomUUID(), OFFENDER, null, type, OptionalInt.empty(),
                true, 10L, 40L, -50L, 0L, 600L, resolution);
    }

    /** Every crime id this mod ships, so a twelfth one cannot quietly become capital. */
    private static List<ResourceLocation> everyOtherCrime() {
        return List.of(CrimeIds.HARM_VILLAGER, CrimeIds.KILL_VILLAGER, CrimeIds.ASSAULT_GUARD,
                CrimeIds.JAILBREAK, CrimeIds.KIDNAP, CrimeIds.THEFT, CrimeIds.MUGGING_MURDER,
                CrimeIds.EXTORTION, CrimeIds.MUGGING, CrimeIds.ATTEMPTED_MUGGING,
                CrimeIds.ASSAULT_PLAYER, CrimeIds.MURDER_PLAYER, CrimeIds.POSSESS_CONTRABAND,
                CrimeIds.AIDING_A_CRIMINAL);
    }

    @Test
    void onlyGuardKillingQualifies() {
        for (ResourceLocation type : everyOtherCrime()) {
            assertFalse(CapitalSentenceService.qualifies(
                            List.of(caseOf(type, Resolution.UNRESOLVED)), true, true, false, false),
                    type + " must never produce a capital sentence");
        }
        assertTrue(CapitalSentenceService.qualifies(
                List.of(caseOf(CrimeIds.KILL_GUARD, Resolution.UNRESOLVED)), true, true, false, false));
    }

    @Test
    void aPileOfOtherCrimesNeverAddsUpToOne() {
        List<CrimeRecord> everything = everyOtherCrime().stream()
                .map(type -> caseOf(type, Resolution.UNRESOLVED))
                .toList();
        assertFalse(CapitalSentenceService.qualifies(everything, true, true, false, false),
                "no Heat, charge count or combination of other offences may escalate to capital");
    }

    /** The mugging branch keeps precedence: robbing a guard to death is a mugging murder. */
    @Test
    void muggingMurderOfAGuardIsNotCapital() {
        assertFalse(CapitalSentenceService.qualifies(
                        List.of(caseOf(CrimeIds.MUGGING_MURDER, Resolution.UNRESOLVED)),
                        true, true, false, false),
                "a mugging that killed a guard files mugging_murder and is not capital");
        assertFalse(CapitalSentenceService.qualifyingOffence(CrimeIds.MUGGING_MURDER));
        assertTrue(CapitalSentenceService.qualifyingOffence(CrimeIds.KILL_GUARD));
    }

    @Test
    void disabledConfigNeverEscalates() {
        List<CrimeRecord> guardKilling = List.of(caseOf(CrimeIds.KILL_GUARD, Resolution.UNRESOLVED));
        assertFalse(CapitalSentenceService.qualifies(guardKilling, false, true, false, false),
                "with the feature disabled a guard killing is an ordinary custodial sentence");
        assertFalse(CapitalSentenceService.qualifies(guardKilling, true, false, false, false),
                "with guardKillingIsCapital off nothing qualifies at all");
    }

    @Test
    void aSettledGuardKillingDoesNotQualify() {
        for (Resolution resolution : List.of(Resolution.SERVED, Resolution.FINED, Resolution.PARDONED,
                Resolution.EXPIRED)) {
            assertFalse(CapitalSentenceService.qualifies(
                            List.of(caseOf(CrimeIds.KILL_GUARD, resolution)), true, true, false, false),
                    "a " + resolution + " case has been answered for and cannot condemn anybody again");
        }
    }

    /** Escaping is not forgiveness, here as everywhere else in the ledger. */
    @Test
    void anEscapedGuardKillingStillQualifies() {
        assertTrue(CapitalSentenceService.qualifies(
                List.of(caseOf(CrimeIds.KILL_GUARD, Resolution.ESCAPED)), true, true, false, false));
    }

    @Test
    void anEmptyOrNullAssessmentQualifiesNobody() {
        assertFalse(CapitalSentenceService.qualifies(List.of(), true, true, false, false));
        assertFalse(CapitalSentenceService.qualifies(null, true, true, false, false));
    }

    @Test
    void npcOffendersAreOnlyEligibleWhenTheOperatorSaysSo() {
        List<CrimeRecord> guardKilling = List.of(caseOf(CrimeIds.KILL_GUARD, Resolution.UNRESOLVED));
        assertFalse(CapitalSentenceService.qualifies(guardKilling, true, true, true, false),
                "a villager offender is not eligible by default");
        assertTrue(CapitalSentenceService.qualifies(guardKilling, true, true, true, true));
        assertTrue(CapitalSentenceService.qualifies(guardKilling, true, true, false, false),
                "the opt-in is about NPC offenders and must not affect players");
    }
}
