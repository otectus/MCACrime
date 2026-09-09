package dev.otectus.mcacrime;

import dev.otectus.mcacrime.crime.type.CrimeAwareness;
import dev.otectus.mcacrime.crime.type.CrimeIds;
import dev.otectus.mcacrime.memory.*;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static dev.otectus.mcacrime.memory.ApologyStatus.*;
import static org.junit.jupiter.api.Assertions.*;

class ApologyStatusTest {
    private final UUID actor = UUID.randomUUID(), victim = UUID.randomUUID();

    private VictimCrimeMemory assault(long now) {
        return VictimMemoryService.create(actor, victim, UUID.randomUUID(), CrimeMemoryCategory.ASSAULT,
                now, CrimeAwareness.defaults(CrimeIds.HARM_VILLAGER), 1, false);
    }

    private ApologyStatus status(long now, VictimCrimeMemory... memories) {
        return ApologyStatus.evaluate(List.of(memories), actor, now, 24000);
    }

    @Test void oneHitExplainsTheWaitAndBecomesEligibleAtExactlyOneMinute() {
        var memory = assault(1000);
        assertEquals(GIVE_SPACE, status(1000, memory));
        assertEquals(GIVE_SPACE, status(2199, memory));
        assertEquals(READY, status(2200, memory));
        assertEquals(1, memory.repeatCount());
    }

    @Test void missingOrOtherPlayersHistoryCannotGrantAnApology() {
        assertEquals(NOT_NEEDED, status(2200));
        assertEquals(NOT_NEEDED, ApologyStatus.evaluate(List.of(assault(1000)), UUID.randomUUID(), 2200, 24000));
    }

    @Test void acceptedApologySurvivesReloadAndCannotBeFarmed() {
        var original = assault(1000);
        var accepted = original.reconcile(2200, 1, true, false, false);
        assertEquals(ALREADY_APOLOGIZED, status(2200, accepted));
        assertEquals(ALREADY_APOLOGIZED, status(100000, VictimCrimeMemory.load(accepted.save())));
        assertTrue(accepted.anger() < original.angerAt(2200, 1));
        assertEquals(original.fearAt(2200, 1), accepted.fear());
    }

    @Test void repeatedOffenseRestartsTheInitialWaitAndRetainsTheApologyCooldown() {
        var accepted = assault(1000).reconcile(2200, 1, true, false, false);
        var repeated = accepted.merge(assault(3000), 3000, 1);
        assertEquals(GIVE_SPACE, status(4199, repeated));
        assertEquals(COOLDOWN, status(4200, repeated));
        assertEquals(COOLDOWN, status(26199, repeated));
        assertEquals(READY, status(26200, repeated));
    }

    @Test void oneEligibleMemoryIsEnoughAndFreshMemoriesRemainIneligible() {
        var old = assault(1000);
        var fresh = assault(2100);
        var accepted = old.reconcile(2200, 1, true, false, false);
        assertEquals(READY, status(2200, fresh, accepted, old));
        assertEquals(READY, status(2200, old, accepted, fresh));
        assertEquals(GIVE_SPACE, ApologyStatus.forMemory(fresh, 2200, 24000));
        assertEquals(GIVE_SPACE, status(2200, fresh, accepted));
        assertEquals(GIVE_SPACE, status(2200, accepted, fresh));
    }

    @Test void duplicateDamageObservationDoesNotRestartTheWait() {
        var memory = assault(1000);
        var duplicate = memory.merge(memory, 2199, 1);
        assertEquals(READY, status(2200, duplicate));
        assertEquals(1, duplicate.repeatCount());
    }
}
