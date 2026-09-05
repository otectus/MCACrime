package dev.otectus.mcacrime;

import dev.otectus.mcacrime.ledger.CrimeFlag;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Flags ride in the crime record's context map as a string, which means the encoding is a save-file
 * format and not an implementation detail.
 *
 * <p>The property that matters most is the last one: a flag written by a newer build must be ignored
 * rather than thrown over. A world opened in an older jar has to keep loading, and a token nothing
 * here understands is not a reason to lose the case it was attached to.
 */
class CrimeFlagTest {

    @Test
    void everySetSurvivesTheRoundTrip() {
        for (CrimeFlag flag : CrimeFlag.values()) {
            EnumSet<CrimeFlag> one = EnumSet.of(flag);
            assertEquals(one, CrimeFlag.decode(CrimeFlag.encode(one)));
        }
        EnumSet<CrimeFlag> all = EnumSet.allOf(CrimeFlag.class);
        assertEquals(all, CrimeFlag.decode(CrimeFlag.encode(all)));
    }

    @Test
    void anEmptySetEncodesToAnEmptyStringAndBack() {
        EnumSet<CrimeFlag> none = EnumSet.noneOf(CrimeFlag.class);
        assertEquals("", CrimeFlag.encode(none));
        assertTrue(CrimeFlag.decode("").isEmpty());
        assertTrue(CrimeFlag.decode(null).isEmpty());
        assertTrue(CrimeFlag.decode("   ").isEmpty());
    }

    @Test
    void theEncodingIsLowercaseAndCommaSeparated() {
        assertEquals("caught_in_act,mandatory_custody",
                CrimeFlag.encode(EnumSet.of(CrimeFlag.MANDATORY_CUSTODY, CrimeFlag.CAUGHT_IN_ACT)),
                "enum order, not insertion order, so the same set always reads the same way");
    }

    @Test
    void anUnknownTokenIsSkippedRatherThanThrown() {
        EnumSet<CrimeFlag> decoded = CrimeFlag.decode("caught_in_act,invented_by_a_newer_build,npc_offender");
        assertEquals(EnumSet.of(CrimeFlag.CAUGHT_IN_ACT, CrimeFlag.NPC_OFFENDER), decoded);
    }

    @Test
    void whitespaceAndCaseAreForgiven() {
        EnumSet<CrimeFlag> decoded = CrimeFlag.decode(" CAUGHT_IN_ACT , npc_offender ,, ");
        assertEquals(EnumSet.of(CrimeFlag.CAUGHT_IN_ACT, CrimeFlag.NPC_OFFENDER), decoded);
        assertFalse(decoded.contains(CrimeFlag.MANDATORY_CUSTODY));
    }
}
