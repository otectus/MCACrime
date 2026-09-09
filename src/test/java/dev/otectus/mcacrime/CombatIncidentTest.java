package dev.otectus.mcacrime;

import dev.otectus.mcacrime.detect.CombatEncounters;
import dev.otectus.mcacrime.detect.CombatIncidentProcessor;
import dev.otectus.mcacrime.detect.DamageFinality;
import dev.otectus.mcacrime.incident.IncidentService;
import dev.otectus.mcacrime.ledger.CrimeRecord;
import dev.otectus.mcacrime.ledger.Resolution;
import dev.otectus.mcacrime.state.world.CrimeWorldData;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import static dev.otectus.mcacrime.detect.DamageFinality.Outcome.*;
import static dev.otectus.mcacrime.detect.CombatEncounters.Basis.*;
import static org.junit.jupiter.api.Assertions.*;

class CombatIncidentTest {
    final UUID player = UUID.randomUUID(), villager = UUID.randomUUID();
    final CombatIncidentProcessor processor = new CombatIncidentProcessor();
    final CrimeWorldData world = new CrimeWorldData();
    final List<CombatIncidentProcessor.Assessment> committed = new ArrayList<>();
    int karmaWrites;
    CombatIncidentProcessor.Hit hit(long at) { return hit(player, villager, at, true, true, true, false, false); }
    CombatIncidentProcessor.Hit hit(UUID actor, UUID victim, long at, boolean playerActor,
            boolean harm, boolean kill, boolean lawfulNpc, boolean raid) {
        return new CombatIncidentProcessor.Hit(UUID.randomUUID(), actor, victim, at, playerActor, harm, kill, lawfulNpc, raid);
    }
    boolean commit(CombatIncidentProcessor.Assessment assessment) {
        var hit = assessment.hit();
        var record = new CrimeRecord(hit.id(), hit.attacker(), hit.victim(),
                ResourceLocation.fromNamespaceAndPath("mcacrime", assessment.outcome() == KILL ? "kill_villager" : "harm_villager"),
                OptionalInt.empty(), false, hit.at(), 10, -10, 0, 0, Resolution.UNRESOLVED);
        return IncidentService.commitPrepared(world, record, () -> karmaWrites++, () -> {},
                () -> committed.add(assessment)).isPresent();
    }
    boolean complete(CombatIncidentProcessor.Hit hit, DamageFinality finality, boolean dead) {
        return processor.complete(hit, finality.resolve(dead), 40, this::commit);
    }
    DamageFinality damage(double amount) { return new DamageFinality(() -> false, () -> amount); }

    @Test void armorReducedNominallyLethalHitCommitsOneAssault() {
        AtomicReference<Double> amount = new AtomicReference<>(30.0);
        var finality = new DamageFinality(() -> false, amount::get); amount.set(2.0);
        assertTrue(complete(hit(10), finality, false));
        assertEquals(HARM, committed.get(0).outcome()); assertEquals(1, karmaWrites);
    }
    @Test void finalDamageCancellationDoesNotChargeOrConsumeHarmCooldown() {
        AtomicBoolean canceled = new AtomicBoolean(false);
        var finality = new DamageFinality(canceled::get, () -> 10); canceled.set(true);
        assertFalse(complete(hit(10), finality, false));
        assertTrue(complete(hit(11), damage(1), false)); assertEquals(1, karmaWrites);
    }
    @Test void finalZeroAmountShieldAndFullAbsorptionDoNotCharge() {
        for (double amount : new double[]{0, -1, Double.NaN, Double.POSITIVE_INFINITY})
            assertFalse(complete(hit(10), damage(amount), false));
        assertEquals(0, karmaWrites);
        assertTrue(complete(hit(11), damage(1), false));
    }
    @Test void totemSurvivalProducesAssaultWithoutMurder() {
        assertTrue(complete(hit(10), damage(100), false));
        assertEquals(HARM, committed.get(0).outcome());
    }
    @Test void canceledDeathProducesAssaultEvenAtZeroHealth() {
        var finality = damage(100); finality.death(() -> true);
        assertTrue(complete(hit(10), finality, true)); assertEquals(HARM, committed.get(0).outcome());
    }
    @Test void deathCanceledByLaterListenerIsReadAtReconciliation() {
        AtomicBoolean canceled = new AtomicBoolean(false); var finality = damage(100);
        finality.death(canceled::get); canceled.set(true);
        assertTrue(complete(hit(10), finality, true)); assertEquals(HARM, committed.get(0).outcome());
    }
    @Test void confirmedDeathCommitsOnlyKillForTheTerminalHit() {
        var finality = damage(100); finality.death(() -> false);
        assertTrue(complete(hit(10), finality, true)); assertEquals(KILL, committed.get(0).outcome());
        assertEquals(1, committed.size()); assertEquals(1, karmaWrites);
    }
    @Test void duplicatePlayerDeathHooksCommitOnlyOnce() {
        var finality = damage(100); finality.death(() -> false); finality.death(() -> false);
        var hit = hit(10); assertTrue(complete(hit, finality, true));
        assertFalse(complete(hit, finality, true)); assertEquals(1, karmaWrites);
    }
    @Test void cancellationOfEitherNestedDeathHookPreventsMurder() {
        var finality = damage(100); finality.death(() -> false); finality.death(() -> true);
        assertTrue(complete(hit(10), finality, true)); assertEquals(HARM, committed.get(0).outcome());
    }
    @Test void revivalBeforeReconciliationPreventsMurder() {
        var finality = damage(100); finality.death(() -> false);
        assertTrue(complete(hit(10), finality, false)); assertEquals(HARM, committed.get(0).outcome());
    }
    @Test void directDeathWithoutDamageCanStillCommitKill() {
        var finality = damage(0); finality.death(() -> false);
        assertTrue(complete(hit(10), finality, true)); assertEquals(KILL, committed.get(0).outcome());
    }
    @Test void laterKillingIsNotLostToEarlierHarmCooldown() {
        assertTrue(complete(hit(10), damage(1), false));
        var finality = damage(100); finality.death(() -> false);
        assertTrue(complete(hit(11), finality, true));
        assertEquals(List.of(HARM, KILL), committed.stream().map(CombatIncidentProcessor.Assessment::outcome).toList());
    }
    @Test void ordinaryFlurryCollapsesButDoesNotExtendCooldown() {
        assertTrue(complete(hit(10), damage(1), false));
        assertFalse(complete(hit(30), damage(1), false));
        assertTrue(complete(hit(50), damage(1), false)); assertEquals(2, karmaWrites);
    }
    @Test void failedCommitDoesNotConsumeTheNextHitsCooldown() {
        assertFalse(processor.complete(hit(10), HARM, 40, assessment -> false));
        assertTrue(complete(hit(11), damage(1), false));
    }
    @Test void callbackReplayCannotReenterTheSameHit() {
        var hit = hit(10);
        assertTrue(processor.complete(hit, HARM, 40, assessment -> {
            assertFalse(processor.complete(hit, HARM, 40, unused -> fail("reentered")));
            return commit(assessment);
        })); assertEquals(1, karmaWrites);
    }
    @Test void provokingThenKillingARetaliatingVillagerRemainsCriminal() {
        assertTrue(complete(hit(10), damage(1), false));
        assertFalse(processor.complete(hit(villager, player, 11, false, false, false, false, false), HARM, 40, this::commit));
        var finality = damage(100); finality.death(() -> false);
        assertTrue(complete(hit(12), finality, true));
        assertEquals(CONTINUED_AGGRESSION, committed.get(1).decision().basis());
        assertEquals(player, committed.get(1).decision().initiator());
    }
    @Test void lawfulGuardResponseCannotCreateSelfDefenseExemption() {
        processor.complete(hit(villager, player, 10, false, false, false, true, false), HARM, 40, this::commit);
        assertTrue(complete(hit(11), damage(1), false));
        assertEquals(CONTINUED_AGGRESSION, committed.get(0).decision().basis());
    }
    @Test void unprovokedNpcAttackAllowsNonlethalDefense() {
        processor.complete(hit(villager, player, 10, false, false, false, false, false), HARM, 40, this::commit);
        assertFalse(complete(hit(11), damage(1), false)); assertEquals(0, karmaWrites);
    }
    @Test void lethalRetaliationNeedsIndependentLethalAuthority() {
        processor.complete(hit(villager, player, 10, false, false, false, false, false), HARM, 40, this::commit);
        var finality = damage(100); finality.death(() -> false);
        assertTrue(complete(hit(11), finality, true));
        assertEquals(EXCESSIVE_DEFENSIVE_FORCE, committed.get(0).decision().basis());
    }
    @Test void nonlethalLegalTargetPermissionDoesNotGrantLethalPermission() {
        assertFalse(processor.complete(hit(player, villager, 10, true, false, true, false, false), HARM, 40, this::commit));
        assertTrue(processor.complete(hit(player, villager, 11, true, false, true, false, false), KILL, 40, this::commit));
    }
    @Test void raidGraceCoversOnlyFirstEligibleNonlethalSplash() {
        assertFalse(processor.complete(hit(player, villager, 10, true, true, true, false, true), HARM, 40, this::commit));
        assertTrue(processor.complete(hit(player, villager, 11, true, true, true, false, true), HARM, 40, this::commit));
    }
    @Test void raidGraceNeverCoversKilling() {
        assertTrue(processor.complete(hit(player, villager, 10, true, true, true, false, true), KILL, 40, this::commit));
    }
    @Test void expiredEncounterCannotJustifyDelayedRevenge() {
        processor.complete(hit(villager, player, 10, false, false, false, false, false), HARM, 40, this::commit);
        assertTrue(complete(hit(210), damage(1), false));
        assertEquals(INITIATING_AGGRESSION, committed.get(0).decision().basis());
    }
    @Test void forgetAndSeparateDimensionProcessorDoNotInheritDefense() {
        processor.complete(hit(villager, player, 10, false, false, false, false, false), HARM, 40, this::commit);
        assertTrue(new CombatIncidentProcessor().complete(hit(11), HARM, 40, this::commit));
        processor.forget(player); assertTrue(complete(hit(12), damage(1), false));
    }
    @Test void unrelatedVictimDoesNotInheritAnAggressorsCooldown() {
        assertTrue(complete(hit(10), damage(1), false));
        assertTrue(processor.complete(hit(player, UUID.randomUUID(), 11, true, true, true, false, false), HARM, 40, this::commit));
    }
    @Test void encounterStoreIsBoundedAndTimeRewindClearsProvenance() {
        var encounters = new CombatEncounters();
        for (int i = 0; i < CombatEncounters.MAX_ENCOUNTERS; i++)
            encounters.record(UUID.randomUUID(), UUID.randomUUID(), 100, false, false, false);
        assertEquals(CombatEncounters.MAX_ENCOUNTERS, encounters.size());
        assertEquals(UNTRACKED, encounters.record(player, villager, 100, false, false, false).basis());
        assertEquals(INITIATING_AGGRESSION, encounters.record(player, villager, 0, false, false, false).basis());
        assertEquals(1, encounters.size());
    }
}
