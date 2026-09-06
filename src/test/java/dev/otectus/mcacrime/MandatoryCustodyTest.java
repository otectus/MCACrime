package dev.otectus.mcacrime;

import dev.otectus.mcacrime.crime.Band;
import dev.otectus.mcacrime.economy.SettlementPolicy;
import dev.otectus.mcacrime.economy.SettlementQuote;
import dev.otectus.mcacrime.ledger.CrimeContext;
import dev.otectus.mcacrime.ledger.CrimeFlag;
import dev.otectus.mcacrime.ledger.CrimeRecord;
import dev.otectus.mcacrime.ledger.Resolution;
import dev.otectus.mcacrime.state.world.CrimeWorldData;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A thief caught in the act cannot buy their way out (spec §"Guards and thief arrests").
 *
 * <p>The fine-versus-jail decision has exactly one fork in the whole mod. Until 0.5.1 it read "fines
 * are enabled and an amount could be calculated", which is a statement about money and says nothing
 * about what was done. {@link CrimeFlag#MANDATORY_CUSTODY} is the third term, and it overrides both:
 * an offence that is not fine-payable ends in custody or not at all.
 *
 * <p>The rule moved in 0.6.0 from a boolean on the guard screen to {@code SettlementPolicy}, which is
 * where the price is decided — so it now closes the fine branch for the dossier and {@code /crime
 * payfine} as well, and not only for the guard who happened to be standing there.
 */
class MandatoryCustodyTest {

    private static final UUID OFFENDER = UUID.randomUUID();
    private static final ResourceLocation THEFT = new ResourceLocation("mcacrime", "theft");
    private static final SettlementPolicy.Settings FINES_ON =
            new SettlementPolicy.Settings(true, 10, 2, 100, 1.0D, true, 8);
    private static final SettlementPolicy.Settings FINES_OFF =
            new SettlementPolicy.Settings(false, 10, 2, 100, 1.0D, true, 8);

    private static CrimeWorldData ledgerWith(String encodedFlags) {
        Map<String, String> context = encodedFlags == null
                ? Map.of()
                : Map.of(CrimeContext.FLAGS, encodedFlags);
        CrimeWorldData data = new CrimeWorldData();
        data.addRecord(new CrimeRecord(UUID.randomUUID(), OFFENDER, null, THEFT, OptionalInt.empty(),
                null, true, Set.of(), 100L, 12L, -5L, 12L, 0L, Resolution.UNRESOLVED, 0L, List.of(),
                null, context));
        return data;
    }

    private static boolean finable(CrimeWorldData data, SettlementPolicy.Settings settings) {
        return SettlementPolicy.quote(data, OFFENDER, 20L, Band.GREY, List.of(), true, 0L, settings).ok();
    }

    private static boolean finable(EnumSet<CrimeFlag> flags) {
        return finable(ledgerWith(flags.isEmpty() ? null : CrimeFlag.encode(flags)), FINES_ON);
    }

    @Test
    void anOrdinaryChargeWithFinesOnIsFinable() {
        assertTrue(finable(EnumSet.noneOf(CrimeFlag.class)));
    }

    @Test
    void mandatoryCustodyForcesFalseEvenWithAnAmountAndFinesOn() {
        assertFalse(finable(EnumSet.of(CrimeFlag.MANDATORY_CUSTODY)));
    }

    @Test
    void mandatoryCustodyAlongsideOtherFlagsStillForcesFalse() {
        assertFalse(finable(EnumSet.of(CrimeFlag.CAUGHT_IN_ACT, CrimeFlag.NPC_OFFENDER,
                CrimeFlag.MANDATORY_CUSTODY)));
    }

    @Test
    void theOtherFlagsAloneDoNotCloseTheFineBranch() {
        assertTrue(finable(EnumSet.of(CrimeFlag.CAUGHT_IN_ACT, CrimeFlag.NPC_OFFENDER)));
    }

    @Test
    void finesOffOrJailableHeatAreStillTheOriginalTerms() {
        assertFalse(finable(ledgerWith(null), FINES_OFF));
        // Heat at the jailable threshold: served, not paid, flag or no flag.
        assertFalse(SettlementPolicy.quote(ledgerWith(null), OFFENDER, 100L, Band.GREY, List.of(), true,
                0L, FINES_ON).ok());
    }

    @Test
    void oneUnpayableChargeClosesTheBranchForTheWholeSettlement() {
        // The quote is one act of payment. Letting the payable cases through would leave the player
        // believing they had settled up while the charge that mattered stayed open.
        CrimeWorldData data = ledgerWith(null);
        data.addRecord(new CrimeRecord(UUID.randomUUID(), OFFENDER, null, THEFT, OptionalInt.empty(),
                null, true, Set.of(), 200L, 8L, -5L, 20L, 0L, Resolution.UNRESOLVED, 0L, List.of(), null,
                Map.of(CrimeContext.FLAGS, CrimeFlag.encode(EnumSet.of(CrimeFlag.MANDATORY_CUSTODY)))));

        SettlementQuote quote = SettlementPolicy.quote(data, OFFENDER, 20L, Band.GREY, List.of(), true,
                0L, FINES_ON);
        assertFalse(quote.ok());
    }

    @Test
    void theFlagSurvivesTheContextEncodingItIsCarriedIn() {
        // The flag reaches the fork through the record's context map, so the round-trip is part of
        // the rule rather than a detail of the ledger.
        String encoded = CrimeFlag.encode(EnumSet.of(CrimeFlag.CAUGHT_IN_ACT, CrimeFlag.MANDATORY_CUSTODY));
        assertFalse(finable(ledgerWith(encoded), FINES_ON));
        assertTrue(CrimeFlag.decode(encoded).contains(CrimeFlag.MANDATORY_CUSTODY));
    }
}
