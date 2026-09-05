package dev.otectus.mcacrime;

import dev.otectus.mcacrime.mug.npc.NpcMugAbortReason;
import dev.otectus.mcacrime.mug.npc.NpcMugSession;
import dev.otectus.mcacrime.mug.npc.NpcMuggingService;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The mug session's timer and the one-session-per-victim rule (0.5.1).
 *
 * <p>Both are invariants rather than behaviour: progress that could exceed its requirement would make
 * the victim's bar lie, and a victim that two thieves could claim at once is the "no overlapping
 * muggings" rule failing silently — the second thief would take a second cut of a wallet the first
 * one is already emptying.
 */
class NpcMugSessionTest {

    private static final ResourceLocation OVERWORLD = ResourceLocation.fromNamespaceAndPath("minecraft", "overworld");

    private static NpcMugSession session(UUID thief, UUID victim, int required) {
        return new NpcMugSession(UUID.randomUUID(), thief, victim, OVERWORLD, 100L, required);
    }

    @Test
    void progressNeverExceedsTheRequirement() {
        NpcMugSession session = session(UUID.randomUUID(), UUID.randomUUID(), 5);
        for (int i = 0; i < 50; i++) {
            session.advance();
        }
        assertEquals(5, session.progress());
        assertTrue(session.complete());
    }

    @Test
    void aSessionIsNotCompleteUntilItsLastTick() {
        NpcMugSession session = session(UUID.randomUUID(), UUID.randomUUID(), 3);
        session.advance();
        session.advance();
        assertFalse(session.complete());
        session.advance();
        assertTrue(session.complete());
    }

    @Test
    void aRequirementBelowOneIsRaisedRatherThanCompletingInstantly() {
        NpcMugSession session = session(UUID.randomUUID(), UUID.randomUUID(), 0);
        assertEquals(1, session.requiredTicks());
        assertFalse(session.complete());
    }

    @Test
    void onlyOneThiefCanClaimAVictim() {
        UUID victim = UUID.randomUUID();
        NpcMugSession first = session(UUID.randomUUID(), victim, 20);
        NpcMugSession second = session(UUID.randomUUID(), victim, 20);
        assertTrue(NpcMuggingService.claim(first));
        assertFalse(NpcMuggingService.claim(second), "a second thief must not claim the same victim");
        assertTrue(NpcMuggingService.isVictim(victim));
        assertEquals(first.transactionId(),
                NpcMuggingService.sessionFor(victim).orElseThrow().transactionId());
        assertEquals(first.transactionId(),
                NpcMuggingService.sessionForThief(first.thiefId()).orElseThrow().transactionId());
        // Deliberately not aborted: ending a session sends packets and posts events, which needs a
        // server. The claim is the whole rule, and a random victim id leaves nothing behind that any
        // other test could see.
    }

    @Test
    void everyAbortReasonNamesAnOutcomeKeyOfItsOwn() {
        for (NpcMugAbortReason reason : NpcMugAbortReason.values()) {
            assertTrue(reason.outcomeKey().startsWith("gui.mcacrime.outcome.npc_mug."), reason.name());
            assertEquals(reason.outcomeKey().toLowerCase(java.util.Locale.ROOT), reason.outcomeKey());
        }
    }
}
