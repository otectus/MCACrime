package dev.otectus.mcacrime;

import dev.otectus.mcacrime.frisk.SeizureKind;
import dev.otectus.mcacrime.frisk.StolenGoodsTransfer;
import dev.otectus.mcacrime.frisk.StolenGoodsTransfer.Disposition;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Whose a stolen slot is decides what taking it means (0.7.7): the owner recovers it, a lawful search
 * seals it for the owner, anybody else steals it again with the owner still named.
 */
class StolenGoodsTransferTest {

    private static final UUID OWNER = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final UUID OTHER = UUID.fromString("00000000-0000-0000-0000-0000000000b2");

    @Test
    void theOwnerAlwaysRecoversWhateverKindOfSearchItIs() {
        for (SeizureKind kind : SeizureKind.values()) {
            assertEquals(Disposition.RECOVERY, StolenGoodsTransfer.disposition(OWNER, OWNER, kind), kind.name());
        }
    }

    @Test
    void aLawfulSearchSealsItForTheOwnerAndAnybodyElseStealsIt() {
        assertEquals(Disposition.EVIDENCE, StolenGoodsTransfer.disposition(OTHER, OWNER, SeizureKind.LAWFUL_SEARCH));
        assertEquals(Disposition.THEFT, StolenGoodsTransfer.disposition(OTHER, OWNER, SeizureKind.CRIMINAL_SEIZURE));
        assertEquals(Disposition.THEFT, StolenGoodsTransfer.disposition(null, OWNER, SeizureKind.CRIMINAL_SEIZURE),
                "an unknown searcher is never the owner");
    }
}
