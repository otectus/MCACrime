package dev.otectus.mcacrime;

import dev.otectus.mcacrime.mug.npc.MuggingThreats;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The mugging threat ledger (0.7.7): written when a hold-up starts, honoured while it runs and for the
 * recovery window after it ends, and never for anybody else.
 */
class MuggingThreatsTest {

    private static final UUID THIEF = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID VICTIM = UUID.fromString("00000000-0000-0000-0000-00000000000b");
    private static final UUID OTHER = UUID.fromString("00000000-0000-0000-0000-00000000000c");

    @AfterEach
    void clear() {
        MuggingThreats.clear();
    }

    @Test
    void anOpenThreatJustifiesTheVictimAndNobodyElse() {
        MuggingThreats.opened(UUID.randomUUID(), THIEF, VICTIM, 100L);
        assertTrue(MuggingThreats.threatens(THIEF, VICTIM, 100L, 0L));
        assertTrue(MuggingThreats.threatens(THIEF, VICTIM, 5_000L, 0L), "an open hold-up has no window to run out");
        assertFalse(MuggingThreats.threatens(THIEF, OTHER, 150L, 1200L), "a bystander is not its victim");
        assertFalse(MuggingThreats.threatens(OTHER, VICTIM, 150L, 1200L), "another villager did not threaten");
        assertFalse(MuggingThreats.threatens(THIEF, VICTIM, 99L, 1200L), "nothing before the threat existed");
    }

    @Test
    void aClosedThreatLastsExactlyTheRecoveryWindow() {
        UUID transaction = UUID.randomUUID();
        MuggingThreats.opened(transaction, THIEF, VICTIM, 100L);
        MuggingThreats.closed(transaction, THIEF, VICTIM, 200L);
        assertTrue(MuggingThreats.threatens(THIEF, VICTIM, 200L, 1200L), "the instant it ended");
        assertTrue(MuggingThreats.threatens(THIEF, VICTIM, 1399L, 1200L), "the last tick of the window");
        assertFalse(MuggingThreats.threatens(THIEF, VICTIM, 1400L, 1200L), "the first tick after it");
        assertFalse(MuggingThreats.threatens(THIEF, VICTIM, 200L, 0L), "a zero window ends with the session");
        assertFalse(MuggingThreats.threatens(THIEF, VICTIM, 201L, 0L), "a zero window ends with the session");
        assertFalse(MuggingThreats.threatens(THIEF, VICTIM, 150L, 1200L),
                "a clock set back before the end does not reopen it");
    }

    @Test
    void closingIsIdempotentAndBoundToItsOwnTransaction() {
        UUID first = UUID.randomUUID();
        MuggingThreats.opened(first, THIEF, VICTIM, 100L);
        MuggingThreats.closed(UUID.randomUUID(), THIEF, VICTIM, 110L);
        assertTrue(MuggingThreats.find(THIEF, VICTIM).orElseThrow().active(), "another transaction cannot close it");
        MuggingThreats.closed(first, THIEF, VICTIM, 120L);
        MuggingThreats.closed(first, THIEF, VICTIM, 900L);
        assertEquals(120L, MuggingThreats.find(THIEF, VICTIM).orElseThrow().endedAt(), "a replayed close moves nothing");

        UUID second = UUID.randomUUID();
        MuggingThreats.opened(second, THIEF, VICTIM, 2000L);
        assertTrue(MuggingThreats.find(THIEF, VICTIM).orElseThrow().active(), "a new hold-up replaces the old one");
        assertEquals(second, MuggingThreats.find(THIEF, VICTIM).orElseThrow().transactionId());
    }

    @Test
    void expiryDropsOnlyClosedThreatsPastTheirWindow() {
        UUID done = UUID.randomUUID();
        MuggingThreats.opened(done, THIEF, VICTIM, 100L);
        MuggingThreats.closed(done, THIEF, VICTIM, 200L);
        MuggingThreats.opened(UUID.randomUUID(), THIEF, OTHER, 100L);
        assertEquals(0, MuggingThreats.expire(1399L, 1200L));
        assertEquals(1, MuggingThreats.expire(1400L, 1200L));
        assertTrue(MuggingThreats.find(THIEF, VICTIM).isEmpty());
        assertTrue(MuggingThreats.find(THIEF, OTHER).isPresent(), "a running hold-up is never expired");
    }

    @Test
    void theLedgerIsBounded() {
        for (int i = 0; i < 5000; i++) {
            UUID transaction = UUID.randomUUID();
            UUID victim = new UUID(1L, i);
            MuggingThreats.opened(transaction, THIEF, victim, i);
            MuggingThreats.closed(transaction, THIEF, victim, i);
        }
        assertTrue(MuggingThreats.size() <= 4096, "size " + MuggingThreats.size());
    }
}
