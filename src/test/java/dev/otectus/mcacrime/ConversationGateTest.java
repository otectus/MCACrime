package dev.otectus.mcacrime;

import dev.otectus.mcacrime.crime.type.CrimeAwareness;
import dev.otectus.mcacrime.crime.type.CrimeIds;
import dev.otectus.mcacrime.memory.ApologyInteractionPolicy;
import dev.otectus.mcacrime.memory.ApologyStatus;
import dev.otectus.mcacrime.memory.ConversationGate;
import dev.otectus.mcacrime.memory.ConversationGate.Verdict;
import dev.otectus.mcacrime.memory.CrimeMemoryCategory;
import dev.otectus.mcacrime.memory.MemoryInteractionHandler;
import dev.otectus.mcacrime.memory.VictimCrimeMemory;
import dev.otectus.mcacrime.memory.VictimMemoryService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Whether a villager talks to one player, given what it remembers (0.7.7).
 *
 * <p>The report: after an accepted apology for one punch the next click was still refused, and the
 * refusal told the player to apologize. The gate now reads the apology flag, and keeps a villager wary
 * only of something grave.
 */
class ConversationGateTest {

    private static final long NOW = 50_000L;
    private final UUID offender = UUID.randomUUID();
    private final UUID other = UUID.randomUUID();
    private final UUID victim = UUID.randomUUID();

    private VictimCrimeMemory punch(UUID by) {
        return VictimMemoryService.create(by, victim, UUID.randomUUID(), CrimeMemoryCategory.ASSAULT, NOW - 2000L,
                CrimeAwareness.defaults(CrimeIds.HARM_VILLAGER), 1, false);
    }

    private VictimCrimeMemory kidnapping(UUID by) {
        return VictimMemoryService.create(by, victim, UUID.randomUUID(), CrimeMemoryCategory.KIDNAPPING, NOW - 2000L,
                CrimeAwareness.defaults(CrimeIds.KIDNAP), 1, false);
    }

    private static VictimCrimeMemory apologized(VictimCrimeMemory memory) {
        return memory.reconcile(NOW - 1000L, 1, true, false, false);
    }

    @Test
    void anUnapologizedPunchRefusesAsItAlwaysDid() {
        var punch = punch(offender);
        var decision = ConversationGate.decide(offender, List.of(punch), NOW, 1);
        assertEquals(Verdict.REFUSE, decision.verdict());
        assertSame(punch, decision.deciding());
    }

    @Test
    void anAcceptedApologyForOnePunchReopensConversationAtOnce() {
        var forgiven = apologized(punch(offender));
        assertTrue(ConversationGate.blocks(forgiven, NOW, 1),
                "the regression needs a memory still above the thresholds after the apology");
        var decision = ConversationGate.decide(offender, List.of(forgiven), NOW, 1);
        assertEquals(Verdict.ALLOW, decision.verdict(), "an accepted apology was ignored");
        assertNull(decision.deciding());
    }

    @Test
    void aGraveCrimeStaysWaryAfterTheApologyAndSaysForHowLong() {
        var forgiven = apologized(kidnapping(offender));
        var decision = ConversationGate.decide(offender, List.of(forgiven), NOW, 1);
        assertEquals(Verdict.WARY, decision.verdict());
        assertSame(forgiven, decision.deciding());
        assertTrue(decision.ticksRemaining() > 0 && decision.ticksRemaining() < Long.MAX_VALUE);
        long later = NOW + decision.ticksRemaining();
        assertTrue(!ConversationGate.blocks(forgiven, later, 1),
                "the promised wait was not long enough");
        assertEquals(Verdict.ALLOW, ConversationGate.decide(offender, List.of(forgiven), later, 1).verdict());
    }

    @Test
    void moreThanTwoRepeatsAreGraveWhateverTheSeverity() {
        var repeated = punch(offender);
        for (int i = 0; i < 2; i++) {
            repeated = repeated.merge(punch(offender), NOW - 1500L, 1);
        }
        assertEquals(3, repeated.repeatCount());
        assertTrue(ConversationGate.grave(repeated));
        assertEquals(Verdict.WARY, ConversationGate.decide(offender, List.of(apologized(repeated)), NOW, 1).verdict());
        assertTrue(!ConversationGate.grave(punch(offender)), "a single punch is not grave");
    }

    @Test
    void oneUnapologizedBlockOutweighsAnApologizedOne() {
        var forgiven = apologized(punch(offender));
        var outstanding = VictimMemoryService.create(offender, victim, UUID.randomUUID(), CrimeMemoryCategory.ROBBERY,
                NOW - 2000L, CrimeAwareness.robbery(), 1, false);
        var decision = ConversationGate.decide(offender, List.of(forgiven, outstanding), NOW, 1);
        assertEquals(Verdict.REFUSE, decision.verdict());
        assertSame(outstanding, decision.deciding());
    }

    @Test
    void anotherPlayersMemoriesAreIgnored() {
        var someoneElsesKidnapping = kidnapping(other);
        assertEquals(Verdict.ALLOW, ConversationGate.decide(offender, List.of(someoneElsesKidnapping), NOW, 1).verdict(),
                "a bystander was refused for another player's crime");
        var mine = apologized(punch(offender));
        assertEquals(Verdict.ALLOW,
                ConversationGate.decide(offender, List.of(someoneElsesKidnapping, mine), NOW, 1).verdict());
        assertEquals(Verdict.REFUSE, ConversationGate.decide(other, List.of(someoneElsesKidnapping, mine), NOW, 1).verdict(),
                "the actual offender is still refused");
    }

    @Test
    void aFadedMemoryNoLongerBlocks() {
        var punch = punch(offender);
        long faded = NOW + punch.ticksUntilCalm(NOW, 1, ConversationGate.FEAR_BLOCK, ConversationGate.ANGER_BLOCK);
        assertEquals(Verdict.ALLOW, ConversationGate.decide(offender, List.of(punch), faded, 1).verdict());
        assertEquals(Verdict.ALLOW, ConversationGate.decide(offender, List.of(), NOW, 1).verdict());
        assertEquals(Verdict.ALLOW, ConversationGate.decide(null, List.of(punch), NOW, 1).verdict());
    }

    @Test
    void daysAreRoundedUpAndNeverZero() {
        assertEquals(1L, ConversationGate.daysRemaining(1L));
        assertEquals(1L, ConversationGate.daysRemaining(24_000L));
        assertEquals(2L, ConversationGate.daysRemaining(24_001L));
        assertEquals(Long.MAX_VALUE, ConversationGate.daysRemaining(Long.MAX_VALUE));
    }

    @Test
    void theRefusalLineSaysTheTruthAndTheHintOnlyWhenTheGestureWasNotUsed() {
        var direct = ApologyInteractionPolicy.Mode.CONTEXTUAL_DIRECT;
        assertEquals("mcacrime.apologize.give_space", MemoryInteractionHandler.refusalMessage(null,
                ApologyInteractionPolicy.Route.NOT_CLAIMED, ApologyStatus.GIVE_SPACE, direct));
        assertEquals("mcacrime.apologize.cooldown", MemoryInteractionHandler.refusalMessage("",
                ApologyInteractionPolicy.Route.NOT_CLAIMED, ApologyStatus.COOLDOWN, direct));
        assertEquals("mcacrime.apologize.hint", MemoryInteractionHandler.refusalMessage(null,
                ApologyInteractionPolicy.Route.NOT_CLAIMED, ApologyStatus.READY, direct),
                "a sneaking or item-holding click should learn how to apologize");
        assertNull(MemoryInteractionHandler.refusalMessage(null,
                ApologyInteractionPolicy.Route.EXECUTE, ApologyStatus.READY, direct),
                "a gesture that was used does not need the how-to hint");
        assertNull(MemoryInteractionHandler.refusalMessage(null, ApologyInteractionPolicy.Route.NOT_CLAIMED,
                ApologyStatus.READY, ApologyInteractionPolicy.Mode.MENU_ONLY),
                "the gesture hint is wrong when the gesture is switched off");
        assertEquals("mcacrime.apologize.lower_weapon", MemoryInteractionHandler.refusalMessage(
                "mcacrime.apologize.lower_weapon", ApologyInteractionPolicy.Route.REFUSE_WITH_REASON,
                ApologyStatus.READY, direct));
        assertNull(MemoryInteractionHandler.refusalMessage(null, ApologyInteractionPolicy.Route.PASS_THROUGH,
                ApologyStatus.ALREADY_APOLOGIZED, direct));
    }
}
