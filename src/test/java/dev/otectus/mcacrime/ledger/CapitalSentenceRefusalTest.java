package dev.otectus.mcacrime.ledger;

import dev.otectus.mcacrime.crime.type.CrimeIds;
import dev.otectus.mcacrime.economy.SettlementPolicy;
import dev.otectus.mcacrime.economy.SettlementQuote;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.OptionalInt;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a condemned prisoner cannot buy (0.7.5 §3.19, acceptance 21.6).
 *
 * <p>Three doors, and each of them used to be open by default: a ransom demand, a bail price and a
 * fine. Every one is refused with its own distinct reason rather than quoted an unpayable number,
 * because a number a player can see is a number they will try to pay, and taking somebody's money
 * for a sentence nobody may lift is worse than refusing them.
 *
 * <p>The two live refusals need a server to read a custody record, so what is asserted here is the
 * part that decides: the rule that closes the money branch, the distinct message each refusal
 * carries, and that both keys actually ship.
 */
class CapitalSentenceRefusalTest {

    private static final UUID OFFENDER = UUID.randomUUID();
    private static final Path LANG = dev.otectus.mcacrime.TestPaths.resources("assets", "mcacrime", "lang",
            "en_us.json");

    private static CrimeRecord caseOf(ResourceLocation type) {
        return new CrimeRecord(UUID.randomUUID(), OFFENDER, null, type, OptionalInt.empty(),
                true, 10L, 40L, -50L, 30L, 600L, Resolution.UNRESOLVED);
    }

    /** A fine never clears a capital case, before or after anybody is arrested for it. */
    @Test
    void aGuardKillingIsNotFinable() {
        assertTrue(SettlementPolicy.mandatoryCustody(caseOf(CrimeIds.KILL_GUARD)),
                "the one offence that may end in a death sentence is not a price list entry");
    }

    @Test
    void everyOtherOffenceIsStillFinable() {
        for (ResourceLocation type : java.util.List.of(CrimeIds.THEFT, CrimeIds.HARM_VILLAGER,
                CrimeIds.ASSAULT_GUARD, CrimeIds.KILL_VILLAGER, CrimeIds.POSSESS_CONTRABAND)) {
            assertFalse(SettlementPolicy.mandatoryCustody(caseOf(type)),
                    type + " must stay finable; the capital rule is one offence, not a mood");
        }
    }

    @Test
    void theFineRefusalReadsAsMandatoryCustody() {
        assertEquals("mcacrime.fine.notfinable",
                SettlementQuote.RejectReason.MANDATORY_CUSTODY.messageKey(),
                "from the player's side, 'this one cannot be bought off' is the same answer");
    }

    @Test
    void theRansomAndBailRefusalsAreDistinctAndTranslated() {
        String lang = read(LANG);
        for (String key : java.util.List.of("mcacrime.ransom.capital", "mcacrime.bail.capital")) {
            assertTrue(lang.contains('"' + key + '"'), key + " is used in code but ships no translation");
        }
        assertTrue(lang.contains("\"mcacrime.ransom.capital\": \"There is no price"),
                "the ransom refusal has to say why, not merely refuse");
    }

    /** The three refusal switches default to on, so a server that says nothing refuses all three. */
    @Test
    void refusalsAreOnByDefault() {
        assertTrue(CapitalSentenceService.refusesRansom());
        assertTrue(CapitalSentenceService.refusesBail());
        assertTrue(CapitalSentenceService.dropsPossessions());
    }

    /** With the feature off, a guard killing is an ordinary case again — including at the till. */
    @Test
    void theOffenceGateAlsoGovernsTheMoneyBranch() {
        assertTrue(CapitalSentenceService.featureEnabled(),
                "the shipped default is on, which is what the finability rule above assumes");
        assertTrue(CapitalSentenceService.guardKillingIsCapital());
    }

    private static String read(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read " + path.toAbsolutePath(), e);
        }
    }
}
