package dev.otectus.mcacrime;

import dev.otectus.mcacrime.crime.Band;
import dev.otectus.mcacrime.economy.FineService;
import dev.otectus.mcacrime.economy.SettlementPolicy;
import dev.otectus.mcacrime.economy.SettlementQuote;
import dev.otectus.mcacrime.ledger.CrimeCaseService;
import dev.otectus.mcacrime.ledger.CrimeContext;
import dev.otectus.mcacrime.ledger.CrimeFlag;
import dev.otectus.mcacrime.ledger.CrimeRecord;
import dev.otectus.mcacrime.ledger.Resolution;
import dev.otectus.mcacrime.state.world.CrimeWorldData;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The one price (spec §6.1).
 *
 * <p>A fine used to be priced in three places — the guard screen, the dossier, and the payment — under
 * two different rules, so the figure a player agreed to and the figure taken from them matched only by
 * coincidence. These tests pin the two properties that fix is worth having: an offence that must end
 * in custody is refused before a single emerald moves, and the number shown is the number charged.
 */
class SettlementPolicyTest {

    private static final UUID OFFENDER = UUID.randomUUID();
    private static final ResourceLocation THEFT = new ResourceLocation("mcacrime", "theft");
    /** Fine base 10, 2 per Heat, jailable at 100, no Blue discount, outlaws may pay, 8 cases a payment. */
    private static final SettlementPolicy.Settings SETTINGS =
            new SettlementPolicy.Settings(true, 10, 2, 100, 1.0D, true, 8);

    private static CrimeRecord open(long committed, long heat, long fine, EnumSet<CrimeFlag> flags) {
        Map<String, String> context = flags.isEmpty()
                ? Map.of()
                : Map.of(CrimeContext.FLAGS, CrimeFlag.encode(flags));
        return new CrimeRecord(UUID.randomUUID(), OFFENDER, null, THEFT, OptionalInt.empty(), null,
                true, java.util.Set.of(), committed, heat, -5L, fine, 0L, Resolution.UNRESOLVED, 0L,
                List.of(), null, context);
    }

    private static CrimeWorldData ledgerOf(CrimeRecord... records) {
        CrimeWorldData data = new CrimeWorldData();
        for (CrimeRecord record : records) {
            data.addRecord(record);
        }
        return data;
    }

    // ------------------------------------------------------------------ T01

    @Test
    void aMandatoryCustodyCaseIsRefusedBeforeAnythingIsCharged() {
        CrimeRecord murder = open(100L, 20L, 40L, EnumSet.of(CrimeFlag.MANDATORY_CUSTODY));
        CrimeWorldData data = ledgerOf(open(50L, 10L, 20L, EnumSet.noneOf(CrimeFlag.class)), murder);

        SettlementQuote quote = SettlementPolicy.quote(data, OFFENDER, 30L, Band.GREY, List.of(), true,
                0L, SETTINGS);
        assertFalse(quote.ok(), "An offence that ends in custody is not a more expensive fine");
        assertEquals(SettlementQuote.RejectReason.MANDATORY_CUSTODY, quote.reject().orElseThrow());
        assertEquals(0L, quote.amount());

        AtomicLong charged = new AtomicLong(-1L);
        FineService.Payment payment = FineService.pay(data, 0L, quote, amount -> {
            charged.set(amount);
            return true;
        }, CrimeCaseService.ResolutionGate.ALLOW_ALL);

        assertFalse(payment.paid());
        assertEquals(-1L, charged.get(), "The purse must not be touched on a refused quote");
        assertTrue(data.actionableFor(OFFENDER).stream().allMatch(CrimeRecord::actionable),
                "Nothing may be settled by a payment that never happened");
    }

    @Test
    void aRefusalSurvivesThePreflightEvenIfTheGateWouldHaveAllowedIt() {
        // The order matters more than either check: the flag closes the branch before the gate, the
        // purse, or the ledger is consulted at all.
        CrimeWorldData data = ledgerOf(open(10L, 15L, 25L, EnumSet.of(CrimeFlag.MANDATORY_CUSTODY)));
        SettlementQuote quote = SettlementPolicy.quote(data, OFFENDER, 20L, Band.RED, List.of(), true,
                0L, SETTINGS);
        assertFalse(quote.ok());
        assertEquals("mcacrime.fine.notfinable", quote.reject().orElseThrow().messageKey());
    }

    // ------------------------------------------------------------------ T02

    @Test
    void theQuotedAmountIsExactlyWhatThePaymentDebits() {
        CrimeWorldData data = ledgerOf(open(10L, 6L, 25L, EnumSet.noneOf(CrimeFlag.class)),
                open(20L, 4L, 0L, EnumSet.noneOf(CrimeFlag.class)));

        SettlementQuote quote = SettlementPolicy.quote(data, OFFENDER, 10L, Band.GREY, List.of(), true,
                0L, SETTINGS);
        assertTrue(quote.ok());
        assertEquals(2, quote.cases().size());

        AtomicLong charged = new AtomicLong();
        FineService.Payment payment = FineService.pay(data, 0L, quote, amount -> {
            charged.set(amount);
            return true;
        }, CrimeCaseService.ResolutionGate.ALLOW_ALL);

        assertTrue(payment.paid());
        assertEquals(quote.amount(), charged.get(), "The screen's figure and the debit are one number");
        assertEquals(quote.amount(), payment.amount());
        assertEquals(2, payment.settledCaseIds().size());
        assertEquals(0L, payment.newHeat());
        assertTrue(data.actionableFor(OFFENDER).isEmpty(), "A paid case is a settled case");
    }

    @Test
    void theGuardScreenAndTheDossierAreHandedTheSameFigure() {
        CrimeWorldData data = ledgerOf(open(10L, 6L, 25L, EnumSet.noneOf(CrimeFlag.class)),
                open(20L, 4L, 15L, EnumSet.noneOf(CrimeFlag.class)));

        // Both surfaces call the same whole-purse quote; asking twice must not produce two prices.
        SettlementQuote screen = SettlementPolicy.quote(data, OFFENDER, 12L, Band.GREY, List.of(), true,
                0L, SETTINGS);
        SettlementQuote dossier = SettlementPolicy.quote(data, OFFENDER, 12L, Band.GREY, List.of(), true,
                40L, SETTINGS);
        assertEquals(screen.amount(), dossier.amount());
        assertEquals(40L, screen.amount(),
                "The 25 and 15 assessed on the records, not the whole-Heat price");
    }

    // ------------------------------------------------------------------ staleness and the preflight

    @Test
    void aCaseThatMovedUnderTheQuoteRefusesTheWholePayment() {
        CrimeRecord first = open(10L, 6L, 25L, EnumSet.noneOf(CrimeFlag.class));
        CrimeWorldData data = ledgerOf(first, open(20L, 4L, 15L, EnumSet.noneOf(CrimeFlag.class)));
        SettlementQuote quote = SettlementPolicy.quote(data, OFFENDER, 12L, Band.GREY, List.of(), true,
                0L, SETTINGS);

        // Somebody pardons one of the quoted cases between the offer and the click.
        CrimeCaseService.resolve(data, 5L, first.id(), Resolution.PARDONED,
                new ResourceLocation("mcacrime", "test"), "test", null, Map.of(), true,
                CrimeCaseService.ResolutionGate.ALLOW_ALL);

        AtomicLong charged = new AtomicLong(-1L);
        FineService.Payment payment = FineService.pay(data, 6L, quote, amount -> {
            charged.set(amount);
            return true;
        }, CrimeCaseService.ResolutionGate.ALLOW_ALL);
        assertFalse(payment.paid());
        assertEquals(-1L, charged.get(), "A stale quote is refused before the debit, not after it");
    }

    @Test
    void anExpiredQuoteIsRefused() {
        CrimeWorldData data = ledgerOf(open(10L, 6L, 25L, EnumSet.noneOf(CrimeFlag.class)));
        SettlementQuote quote = SettlementPolicy.quote(data, OFFENDER, 12L, Band.GREY, List.of(), true,
                0L, SETTINGS);
        assertFalse(quote.expired(SettlementQuote.VALIDITY_TICKS));
        assertTrue(quote.expired(SettlementQuote.VALIDITY_TICKS + 1L));

        FineService.Payment payment = FineService.pay(data, SettlementQuote.VALIDITY_TICKS + 1L, quote,
                amount -> true, CrimeCaseService.ResolutionGate.ALLOW_ALL);
        assertFalse(payment.paid());
    }

    @Test
    void aVetoingGateStopsThePaymentBeforeTheDebit() {
        CrimeWorldData data = ledgerOf(open(10L, 6L, 25L, EnumSet.noneOf(CrimeFlag.class)));
        SettlementQuote quote = SettlementPolicy.quote(data, OFFENDER, 12L, Band.GREY, List.of(), true,
                0L, SETTINGS);

        List<UUID> vetoed = new ArrayList<>();
        AtomicLong charged = new AtomicLong(-1L);
        FineService.Payment payment = FineService.pay(data, 0L, quote, amount -> {
            charged.set(amount);
            return true;
        }, (record, target) -> {
            vetoed.add(record.id());
            return false;
        });

        assertFalse(payment.paid());
        assertEquals(1, vetoed.size(), "Every quoted case is put to the gate");
        assertEquals(-1L, charged.get(), "A veto is not a failed payment; no payment happened");
    }

    @Test
    void anEmptyPurseTakesNothingAndSettlesNothing() {
        CrimeWorldData data = ledgerOf(open(10L, 6L, 25L, EnumSet.noneOf(CrimeFlag.class)));
        SettlementQuote quote = SettlementPolicy.quote(data, OFFENDER, 12L, Band.GREY, List.of(), true,
                0L, SETTINGS);

        FineService.Payment payment = FineService.pay(data, 0L, quote, amount -> false,
                CrimeCaseService.ResolutionGate.ALLOW_ALL);
        assertFalse(payment.paid());
        assertEquals("mcacrime.fine.need", payment.messageKey());
        assertEquals(quote.amount(), payment.amount(), "The refusal quotes the price back at the player");
        assertEquals(1, data.actionableFor(OFFENDER).size());
    }

    @Test
    void jailableHeatIsServedRatherThanPaid() {
        CrimeWorldData data = ledgerOf(open(10L, 6L, 25L, EnumSet.noneOf(CrimeFlag.class)));
        SettlementQuote quote = SettlementPolicy.quote(data, OFFENDER, 100L, Band.GREY, List.of(), true,
                0L, SETTINGS);
        assertEquals(SettlementQuote.RejectReason.NOT_FINABLE, quote.reject().orElseThrow());
    }
}
