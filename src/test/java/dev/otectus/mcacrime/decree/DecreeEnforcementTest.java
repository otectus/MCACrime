package dev.otectus.mcacrime.decree;

import dev.otectus.mcacrime.api.model.CrimeCommunityKey;
import dev.otectus.mcacrime.crime.Band;
import dev.otectus.mcacrime.economy.DispositionService;
import dev.otectus.mcacrime.economy.SettlementPolicy;
import dev.otectus.mcacrime.enforcement.GuardChallenge;
import dev.otectus.mcacrime.justice.JusticeService;
import dev.otectus.mcacrime.justice.LegalDecision;
import dev.otectus.mcacrime.ledger.CrimeRecord;
import dev.otectus.mcacrime.ledger.Resolution;
import dev.otectus.mcacrime.memory.CrimeReport;
import dev.otectus.mcacrime.state.world.CrimeWorldData;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * How a royal decree reaches the guards (0.7.8): a local ground of the decree's own village, never a
 * case, never custody on its own, settled by its own fine or by surrender -- and never in the way of an
 * ordinary case the same guard can act on.
 */
class DecreeEnforcementTest {

    private static final UUID OFFENDER = UUID.randomUUID();
    private static final CrimeCommunityKey LOCAL = new CrimeCommunityKey(ResourceLocation.fromNamespaceAndPath("minecraft", "overworld"), 1);
    private static final ResourceLocation THEFT = ResourceLocation.fromNamespaceAndPath("mcacrime", "theft");
    private static final JusticeService.Settings EVIDENCE = new JusticeService.Settings(true, false, .75);
    private static final SettlementPolicy.Settings FINES = new SettlementPolicy.Settings(true, 10, 2, 100, .5, true, 8);
    private static final SettlementPolicy.Settings NO_FINES = new SettlementPolicy.Settings(false, 10, 2, 100, .5, true, 8);

    private static LegalDecision decreed(CrimeWorldData data, CrimeCommunityKey jurisdiction, UUID decree) {
        return JusticeService.evaluate(data, OFFENDER, jurisdiction, 10L, EVIDENCE, false, false, false, false,
                false, decree);
    }

    @Test
    void aDecreeIsALocalGroundThatNamesItself() {
        UUID decree = UUID.randomUUID();
        LegalDecision decision = decreed(new CrimeWorldData(), LOCAL, decree);
        assertEquals(Set.of(LegalDecision.Basis.ROYAL_DECREE), decision.basis());
        assertEquals(decree, decision.decree());
        assertTrue(decision.mayChallenge());
        assertFalse(decision.requiresCustody(), "a decree demands an answer, not custody on sight");
        assertTrue(decision.decreeOnly());
        assertEquals("mcacrime.challenge.reason.decree", decision.detentionReasonKey());
    }

    @Test
    void aQuestionAskedForNoVillageNeverSeesADecree() {
        LegalDecision decision = decreed(new CrimeWorldData(), null, UUID.randomUUID());
        assertTrue(decision.basis().isEmpty());
        assertNull(decision.decree());
        assertFalse(decision.mayChallenge());
    }

    @Test
    void aDecreeBasisWithoutItsDecreeCannotBeBuilt() {
        assertThrows(IllegalArgumentException.class, () -> new LegalDecision(OFFENDER, LOCAL, List.of(),
                Set.of(LegalDecision.Basis.ROYAL_DECREE)));
    }

    @Test
    void escapeAndCaptivityStillOutrankADecreeInTheGuardsWords() {
        LegalDecision decision = new LegalDecision(OFFENDER, LOCAL, List.of(),
                Set.of(LegalDecision.Basis.ROYAL_DECREE, LegalDecision.Basis.ESCAPED_PRISONER), UUID.randomUUID());
        assertEquals("mcacrime.challenge.reason.escaped", decision.detentionReasonKey());
        LegalDecision wanted = new LegalDecision(OFFENDER, LOCAL, List.of(),
                Set.of(LegalDecision.Basis.ROYAL_DECREE, LegalDecision.Basis.WANTED), UUID.randomUUID());
        assertEquals("mcacrime.challenge.reason.decree", wanted.detentionReasonKey(), "the decree says more than Heat");
    }

    @Test
    void aDecreeOnlyChallengeOffersTheDecreesFine() {
        CrimeWorldData data = new CrimeWorldData();
        DispositionService.Offer offer = DispositionService.offer(data, decreed(data, LOCAL, UUID.randomUUID()), 0L,
                Band.GREY, 10L, FINES, "minecraft:emerald", 32L);
        assertFalse(offer.quote().ok(), "there is no case to quote");
        assertTrue(offer.settlesDecree());
        assertTrue(offer.payable());
        assertEquals(32L, offer.payableAmount());
        GuardChallenge challenge = new GuardChallenge(UUID.randomUUID(), UUID.randomUUID(), OFFENDER, LOCAL, 0,
                offer.payableAmount(), offer.payable(), 10L, 400L, 0L, offer);
        assertTrue(challenge.canPay(), "a decree-only challenge must let the target pay");
    }

    @Test
    void noFineIsOfferedWhenFinesAreOffOrTheDecreeIsUnpayable() {
        CrimeWorldData data = new CrimeWorldData();
        LegalDecision decision = decreed(data, LOCAL, UUID.randomUUID());
        DispositionService.Offer noFines = DispositionService.offer(data, decision, 0L, Band.GREY, 10L, NO_FINES,
                "minecraft:emerald", 32L);
        assertFalse(noFines.payable());
        DispositionService.Offer unpayable = DispositionService.offer(data, decision, 0L, Band.GREY, 10L, FINES,
                "minecraft:emerald", 0L);
        assertFalse(unpayable.payable());
        GuardChallenge challenge = new GuardChallenge(UUID.randomUUID(), UUID.randomUUID(), OFFENDER, LOCAL, 0,
                unpayable.payableAmount(), unpayable.payable(), 10L, 400L, 0L, unpayable);
        assertFalse(challenge.canPay(), "surrender is then the only answer");
    }

    @Test
    void aLocalCaseIsSettledFirstAndTheDecreeWaitsForTheNextChallenge() {
        CrimeRecord theft = new CrimeRecord(UUID.randomUUID(), OFFENDER, null, THEFT, OptionalInt.empty(), LOCAL,
                true, Set.of(), 1L, 5L, -5L, 20L, 0L, Resolution.UNRESOLVED, 0L, List.of(), null, Map.of());
        CrimeWorldData data = new CrimeWorldData();
        data.addRecord(theft);
        data.addReport(new CrimeReport(UUID.randomUUID(), theft.id(), UUID.randomUUID(), UUID.randomUUID(), OFFENDER,
                THEFT, LOCAL, 1L, 500L, 1F, true));
        UUID decree = UUID.randomUUID();
        LegalDecision decision = decreed(data, LOCAL, decree);
        assertEquals(List.of(theft.id()), decision.caseIds(), "the decree hides no case the guard can act on");
        assertTrue(decision.basis().containsAll(Set.of(LegalDecision.Basis.REPORTED_CASE,
                LegalDecision.Basis.ROYAL_DECREE)));
        assertFalse(decision.decreeOnly());
        DispositionService.Offer offer = DispositionService.offer(data, decision, 5L, Band.GREY, 10L, FINES,
                "minecraft:emerald", 32L);
        assertTrue(offer.quote().ok());
        assertFalse(offer.settlesDecree(), "paying here settles the theft, never the decree with it");
        assertEquals(offer.quote().amount(), offer.payableAmount());
    }

    @Test
    void aWithdrawnDecreeMakesTheDisplayedOfferStale() {
        CrimeWorldData data = new CrimeWorldData();
        UUID decree = UUID.randomUUID();
        DispositionService.Offer shown = DispositionService.offer(data, decreed(data, LOCAL, decree), 0L, Band.GREY,
                10L, FINES, "minecraft:emerald", 32L);
        DispositionService.Offer same = DispositionService.offer(data, decreed(data, LOCAL, decree), 0L, Band.GREY,
                11L, FINES, "minecraft:emerald", 32L);
        assertTrue(DispositionService.current(shown, same, 11L));
        DispositionService.Offer withdrawn = DispositionService.offer(data, decreed(data, LOCAL, null), 0L, Band.GREY,
                11L, FINES, "minecraft:emerald", 32L);
        assertFalse(DispositionService.current(shown, withdrawn, 11L));
        DispositionService.Offer repriced = DispositionService.offer(data, decreed(data, LOCAL, decree), 0L, Band.GREY,
                11L, FINES, "minecraft:emerald", 40L);
        assertFalse(DispositionService.current(shown, repriced, 11L));
    }
}
