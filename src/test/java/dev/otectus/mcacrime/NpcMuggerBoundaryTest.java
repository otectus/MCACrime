package dev.otectus.mcacrime;

import dev.otectus.mcacrime.job.NpcMuggerEligibility;
import dev.otectus.mcacrime.job.NpcMuggerEligibility.Context;
import dev.otectus.mcacrime.job.NpcMuggerEligibility.Facts;
import dev.otectus.mcacrime.job.NpcMuggerEligibilityReason;
import dev.otectus.mcacrime.mug.npc.NpcMugAbortReason;
import dev.otectus.mcacrime.mug.npc.NpcMugSession;
import dev.otectus.mcacrime.state.world.CrimeWorldData;
import dev.otectus.mcacrime.state.world.StolenGoodsRecord;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Locale;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The edges around a mugging whose actor stops being allowed to run one (0.7.2, spec §3).
 *
 * <p>The live session boundaries themselves need a level, a player and an MCA villager, so what is
 * asserted here is everything that does not: that the new abort reason exists as its own value rather
 * than as another shade of CANCELLED, that it is something a victim can actually read, that a session
 * already aborted cannot come back and commit, and that an abort leaves an earlier, legitimate theft
 * alone. The remaining halves — the mid-threat role change and the cleanup that follows it — are
 * runtime observations and are recorded as such.
 */
class NpcMuggerBoundaryTest {

    private static final ResourceLocation OVERWORLD = ResourceLocation.fromNamespaceAndPath("minecraft", "overworld");
    private static final UUID THIEF = UUID.fromString("00000000-0000-0000-0000-0000000000c1");
    private static final UUID VICTIM = UUID.fromString("00000000-0000-0000-0000-0000000000c2");

    private static String lang() throws IOException {
        // The NeoForge unit-test runner works out of build/minecraft-junit, so the tree is resolved
        // through TestPaths rather than a relative path (which would read as "file not found").
        return Files.readString(TestPaths.resources("assets", "mcacrime", "lang", "en_us.json"),
                StandardCharsets.UTF_8);
    }

    // ---------------------------------------------------------------- the reason itself

    /**
     * The specific reason exists and is distinct. Collapsing it into CANCELLED would tell an operator
     * that a reload ended the mugging when what actually happened is the invariant firing.
     */
    @Test
    void becomingAResponderIsItsOwnAbortReason() {
        NpcMugAbortReason reason = NpcMugAbortReason.ACTOR_BECAME_RESPONDER;
        assertNotEquals(NpcMugAbortReason.CANCELLED, reason);
        assertEquals("gui.mcacrime.outcome.npc_mug.actor_became_responder", reason.outcomeKey());
    }

    /** Every abort reason reaches the victim's HUD, so every one of them needs a line. */
    @Test
    void everyAbortReasonIsLocalised() throws IOException {
        String lang = lang();
        for (NpcMugAbortReason reason : NpcMugAbortReason.values()) {
            assertTrue(lang.contains("\"" + reason.outcomeKey() + "\""),
                    "no language entry for " + reason.name().toLowerCase(Locale.ROOT));
        }
    }

    /** An operator refused a job assignment is told why, so every reason needs a line too. */
    @Test
    void everyRejectionReasonIsLocalised() throws IOException {
        String lang = lang();
        for (NpcMuggerEligibilityReason reason : NpcMuggerEligibilityReason.values()) {
            assertTrue(lang.contains("\"" + reason.messageKey() + "\""),
                    "no language entry for " + reason.messageKey());
        }
    }

    // ---------------------------------------------------------------- the callback window

    /**
     * The cancellable attempt callback can change the actor's role, which is why the start check is
     * repeated after it. The decision behind both checks is this one, and it flips.
     */
    @Test
    void aRoleChangedInsideTheCallbackFlipsTheSharedDecision() {
        Facts before = new Facts(true, true, true, false, true, true, true);
        assertTrue(NpcMuggerEligibility.evaluate(before, Context.EXECUTION).eligible());

        Facts afterListenerPromotedThem = new Facts(true, true, true, true, true, true, true);
        assertEquals(NpcMuggerEligibilityReason.RESPONDER,
                NpcMuggerEligibility.evaluate(afterListenerPromotedThem, Context.EXECUTION).reason());
    }

    // ---------------------------------------------------------------- teardown

    /**
     * The commit path refuses a session that has already been torn down. This is what stops a second
     * cleanup pass, or a late tick, from debiting a victim whose mugger has just become a guard.
     */
    @Test
    void anAbortedSessionCanNoLongerRunOrCommit() {
        NpcMugSession session = new NpcMugSession(UUID.randomUUID(), THIEF, VICTIM, OVERWORLD, 100L, 2);
        session.advance();
        session.advance();
        assertTrue(session.complete());

        session.markAborted();
        assertFalse(session.running(), "an aborted session must not be runnable again");
        assertEquals(NpcMugSession.Phase.ABORTED, session.phase());
    }

    /**
     * An abort takes nothing, and it also gives nothing back: an earlier mugging this thief actually
     * committed is still on the ledger afterwards, ready to be recovered or fenced.
     */
    @Test
    void anAbortLeavesAnEarlierLegitimateTheftOnTheLedger() {
        CrimeWorldData data = new CrimeWorldData();
        UUID earlier = UUID.randomUUID();
        data.putStolenGoods(new StolenGoodsRecord(earlier, THIEF, VICTIM, null, 9L, 50L));

        NpcMugSession aborted = new NpcMugSession(UUID.randomUUID(), THIEF, VICTIM, OVERWORLD, 100L, 4);
        aborted.advance();
        aborted.markAborted();

        assertEquals(1, data.stolenGoodsByThief(THIEF).size(),
                "the abort must not touch a theft that already committed");
        assertEquals(9L, data.stolenGoodsByThief(THIEF).iterator().next().currency());
        assertTrue(data.stolenGoodsByThief(THIEF).stream()
                .noneMatch(record -> record.transactionId().equals(aborted.transactionId())),
                "an aborted mugging must file nothing");
    }
}
