package dev.otectus.mcacrime;

import dev.otectus.mcacrime.action.CrimeActionService;
import dev.otectus.mcacrime.crime.type.CrimeAwareness;
import dev.otectus.mcacrime.crime.type.CrimeIds;
import dev.otectus.mcacrime.memory.ApologyClaimLedger;
import dev.otectus.mcacrime.memory.ApologyInteractionPolicy;
import dev.otectus.mcacrime.memory.ApologyInteractionPolicy.Facts;
import dev.otectus.mcacrime.memory.ApologyInteractionPolicy.Mode;
import dev.otectus.mcacrime.memory.ApologyInteractionPolicy.Route;
import dev.otectus.mcacrime.memory.ApologyStatus;
import dev.otectus.mcacrime.memory.CrimeMemoryCategory;
import dev.otectus.mcacrime.memory.VictimCrimeMemory;
import dev.otectus.mcacrime.memory.VictimMemoryService;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The empty-hand apology routing table (spec §12.2), exercised as plain facts.
 *
 * <p>MCA is deliberately absent from the test classpath, so nothing here builds a villager: the point
 * of extracting {@link ApologyInteractionPolicy} is that the whole table is decidable from booleans
 * and a shared {@link ApologyStatus} verdict.
 */
class ApologyInteractionPolicyTest {

    private final UUID actor = UUID.randomUUID(), victim = UUID.randomUUID();

    /** The qualifying gesture: not sneaking, empty main hand, harmless off hand, awake target. */
    private Facts ready() {
        return new Facts(false, false, true, false, true, true, true,
                ApologyStatus.READY, false, false, Mode.CONTEXTUAL_DIRECT);
    }

    private Route route(Facts facts) {
        return ApologyInteractionPolicy.route(facts).route();
    }

    @Test void theQualifyingGestureExecutesOnce() {
        assertEquals(Route.EXECUTE, route(ready()));
        assertEquals("", ApologyInteractionPolicy.route(ready()).reasonKey());
    }

    @Test void aCanceledInteractionIsNeverClaimedBack() {
        // Invariant 16: another mod's cancellation stands, even for a perfectly eligible apology.
        assertEquals(Route.NOT_CLAIMED, route(new Facts(true, false, true, false, true, true, true,
                ApologyStatus.READY, false, false, Mode.CONTEXTUAL_DIRECT)));
    }

    @Test void sneakingStaysAvailableToPickupIntegrations() {
        assertEquals(Route.NOT_CLAIMED, route(new Facts(false, true, true, false, true, true, true,
                ApologyStatus.READY, false, false, Mode.CONTEXTUAL_DIRECT)));
    }

    @Test void anItemInTheMainHandGetsNormalHandling() {
        assertEquals(Route.NOT_CLAIMED, route(new Facts(false, false, false, false, true, true, true,
                ApologyStatus.READY, false, false, Mode.CONTEXTUAL_DIRECT)));
    }

    @Test void aHarmlessOffHandItemApologizesAndAnArmedOffHandRefusesWithTheWeaponReason() {
        // A torch is not a threat; only a weapon in that hand is.
        assertEquals(Route.EXECUTE, route(ready()));
        var armed = ApologyInteractionPolicy.route(new Facts(false, false, true, true, true, true, true,
                ApologyStatus.READY, false, false, Mode.CONTEXTUAL_DIRECT));
        assertEquals(Route.REFUSE_WITH_REASON, armed.route());
        assertEquals("mcacrime.apologize.lower_weapon", armed.reasonKey());
    }

    @Test void noGrievancePassesThroughToNormalInteraction() {
        assertEquals(Route.PASS_THROUGH, route(new Facts(false, false, true, false, true, true, false,
                ApologyStatus.NOT_NEEDED, false, false, Mode.CONTEXTUAL_DIRECT)));
    }

    @Test void anAcceptedApologyDoesNotTrapThePlayerButANewIncidentIsEligibleAgain() {
        assertEquals(Route.PASS_THROUGH, route(new Facts(false, false, true, false, true, true, true,
                ApologyStatus.ALREADY_APOLOGIZED, false, false, Mode.CONTEXTUAL_DIRECT)));
        assertEquals(Route.PASS_THROUGH, route(new Facts(false, false, true, false, true, true, true,
                ApologyStatus.NOT_NEEDED, false, false, Mode.CONTEXTUAL_DIRECT)));
        // A genuinely new eligible incident is what makes the gesture live again.
        assertEquals(Route.EXECUTE, route(ready()));
    }

    @Test void settlingAndCooldownReportTheirOwnReasonAndNeverNoHistory() {
        for (ApologyStatus waiting : List.of(ApologyStatus.GIVE_SPACE, ApologyStatus.COOLDOWN)) {
            var decision = ApologyInteractionPolicy.route(new Facts(false, false, true, false, true, true, true,
                    waiting, false, false, Mode.CONTEXTUAL_DIRECT));
            assertEquals(Route.REFUSE_WITH_REASON, decision.route());
            assertEquals(waiting.reason(), decision.reasonKey());
            assertNotEquals(ApologyStatus.NOT_NEEDED.reason(), decision.reasonKey());
        }
    }

    @Test void theSettlingBoundaryTheGestureReadsIsTheExistingOne() {
        // The shortcut adds no timer of its own: it asks the same evaluation the menu asks.
        VictimCrimeMemory memory = VictimMemoryService.create(actor, victim, UUID.randomUUID(),
                CrimeMemoryCategory.ASSAULT, 1000, CrimeAwareness.defaults(CrimeIds.HARM_VILLAGER), 1, false);
        assertEquals(Route.REFUSE_WITH_REASON, route(atTick(memory, 2199)));
        assertEquals(ApologyStatus.GIVE_SPACE.reason(),
                ApologyInteractionPolicy.route(atTick(memory, 2199)).reasonKey());
        assertEquals(Route.EXECUTE, route(atTick(memory, 2200)));
    }

    private Facts atTick(VictimCrimeMemory memory, long now) {
        ApologyStatus status = ApologyStatus.evaluate(List.of(memory), actor, now, 24000);
        return new Facts(false, false, true, false, true, true, true, status, false, false, Mode.CONTEXTUAL_DIRECT);
    }

    @Test void custodyAndCoercionLeaveTheInteractionToTheFlowThatOwnsIt() {
        assertEquals(Route.NOT_CLAIMED, route(new Facts(false, false, true, false, true, true, true,
                ApologyStatus.READY, true, false, Mode.CONTEXTUAL_DIRECT)));
        var threatened = ApologyInteractionPolicy.route(new Facts(false, false, true, false, true, true, true,
                ApologyStatus.READY, false, true, Mode.CONTEXTUAL_DIRECT));
        assertEquals(Route.REFUSE_WITH_REASON, threatened.route());
        assertEquals("mcacrime.apologize.active_threat", threatened.reasonKey());
    }

    @Test void asleepRemovedOrDisabledNeverExecutes() {
        assertEquals(Route.NOT_CLAIMED, route(new Facts(false, false, true, false, true, false, true,
                ApologyStatus.READY, false, false, Mode.CONTEXTUAL_DIRECT)));
        assertEquals(Route.NOT_CLAIMED, route(new Facts(false, false, true, false, false, true, true,
                ApologyStatus.READY, false, false, Mode.CONTEXTUAL_DIRECT)));
        assertEquals(Route.NOT_CLAIMED, route(new Facts(false, false, true, false, true, true, true,
                ApologyStatus.DISABLED, false, false, Mode.CONTEXTUAL_DIRECT)));
    }

    @Test void menuOnlyModeGivesTheGestureBackToNormalInteraction() {
        assertEquals(Route.NOT_CLAIMED, route(new Facts(false, false, true, false, true, true, true,
                ApologyStatus.READY, false, false, Mode.MENU_ONLY)));
    }

    @Test void theCompanionHandPathOfOneClickIsConsumedOnceAndNotLater() {
        UUID player = UUID.randomUUID(), target = UUID.randomUUID();
        ApologyClaimLedger.clear();
        assertFalse(ApologyClaimLedger.isCompanionPath(player, target, 100));
        ApologyClaimLedger.claim(player, target, 100);
        assertTrue(ApologyClaimLedger.isCompanionPath(player, target, 100));
        // A different villager, a later click, and a departed player are all separate interactions.
        assertFalse(ApologyClaimLedger.isCompanionPath(player, UUID.randomUUID(), 100));
        assertFalse(ApologyClaimLedger.isCompanionPath(player, target, 140));
        ApologyClaimLedger.claim(player, target, 200);
        ApologyClaimLedger.forget(player);
        assertFalse(ApologyClaimLedger.isCompanionPath(player, target, 200));
    }

    @Test void theTrustedContextualRouteRejectsWrongIdentityDimensionAndDistance() {
        ResourceLocation overworld = new ResourceLocation("minecraft", "overworld");
        ResourceLocation nether = new ResourceLocation("minecraft", "the_nether");
        assertTrue(CrimeActionService.contextualReachValid(actor, victim, overworld, overworld, 16.0, true, true));
        assertFalse(CrimeActionService.contextualReachValid(actor, actor, overworld, overworld, 1.0, true, true));
        assertFalse(CrimeActionService.contextualReachValid(actor, null, overworld, overworld, 1.0, true, true));
        assertFalse(CrimeActionService.contextualReachValid(actor, victim, overworld, nether, 1.0, true, true));
        assertFalse(CrimeActionService.contextualReachValid(actor, victim, overworld, overworld, 16.01, true, true));
        assertFalse(CrimeActionService.contextualReachValid(actor, victim, overworld, overworld, 1.0, false, true));
        assertFalse(CrimeActionService.contextualReachValid(actor, victim, overworld, overworld, 1.0, true, false));
    }
}
