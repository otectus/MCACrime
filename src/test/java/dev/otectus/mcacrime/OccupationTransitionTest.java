package dev.otectus.mcacrime;

import dev.otectus.mcacrime.compat.OccupationSnapshot;
import dev.otectus.mcacrime.job.HistoricalProfessionKind;
import dev.otectus.mcacrime.job.OccupationMutator;
import dev.otectus.mcacrime.job.OccupationRequest;
import dev.otectus.mcacrime.job.OccupationSource;
import dev.otectus.mcacrime.job.OccupationStatus;
import dev.otectus.mcacrime.job.OccupationTransaction;
import dev.otectus.mcacrime.job.OccupationTransitionReason;
import dev.otectus.mcacrime.job.OccupationTransitionResult;
import net.minecraft.core.BlockPos;
import dev.otectus.mcacrime.state.world.WorksiteRef;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import javax.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The occupation transaction, with a mutator that fails exactly where the test says.
 *
 * <p>This is the whole reason {@code OccupationMutator} is an interface. Spec §21.3 asks for failure
 * injection after each mutation, partial setter failure, deep offer rollback, clothing and family
 * restoration, ticket loss and reentrant role changes — none of which can be produced by a real
 * villager on demand, and all of which are ordinary here.
 *
 * <p>MCA is absent from this classpath, which is the point: the transaction is Minecraft and JDK types
 * throughout, and nothing it does needs MCA to be loaded to be checked.
 */
class OccupationTransitionTest {

    private static final UUID VILLAGER = UUID.fromString("00000000-0000-0000-0000-0000000000aa");
    private static final WorksiteRef STATION = WorksiteRef.of(
            new ResourceLocation("minecraft", "overworld"), new BlockPos(10, 64, 10));
    private static final WorksiteRef OLD_SITE = WorksiteRef.of(
            new ResourceLocation("minecraft", "overworld"), new BlockPos(-30, 70, 5));

    /** Which step should fail. {@code null} means everything works. */
    private enum Step { RESERVE, ADOPT, PROFESSION, OFFERS, JOB_SITE, XP, VERIFY }

    private static class FakeMutator implements OccupationMutator {

        final List<String> journal = new ArrayList<>();
        @Nullable Step failAt;
        @Nullable OccupationSnapshot snapshot = snapshotOf(OLD_SITE, 0);
        boolean oldTicketRecoverable = true;
        boolean restoreFails;

        @Override
        public Optional<OccupationSnapshot> snapshot() {
            journal.add("snapshot");
            return Optional.ofNullable(snapshot);
        }

        @Override
        public boolean reserveTicket(WorksiteRef site) {
            journal.add("reserve:" + site.pos().getX());
            if (site.equals(OLD_SITE)) {
                return oldTicketRecoverable;
            }
            return failAt != Step.RESERVE;
        }

        @Override
        public boolean adoptTicket(WorksiteRef site) {
            journal.add("adopt");
            return failAt != Step.ADOPT;
        }

        @Override
        public void releaseTicket(WorksiteRef site) {
            journal.add("release:" + site.pos().getX());
        }

        @Override
        public boolean releaseOldJobSite(WorksiteRef site) {
            journal.add("releaseOld");
            return true;
        }

        @Override
        public void clearOccupationalMemories() {
            journal.add("memories");
        }

        @Override
        public boolean applyProfession() {
            journal.add("profession");
            return failAt != Step.PROFESSION;
        }

        @Override
        public boolean clearOffers() {
            journal.add("offers");
            return failAt != Step.OFFERS;
        }

        @Override
        public boolean setJobSite(WorksiteRef site) {
            journal.add("jobSite");
            return failAt != Step.JOB_SITE;
        }

        @Override
        public boolean applyXpFloor() {
            journal.add("xp");
            return failAt != Step.XP;
        }

        @Override
        public boolean verify(@Nullable WorksiteRef site) {
            journal.add("verify");
            return failAt != Step.VERIFY;
        }

        @Override
        public boolean restore(OccupationSnapshot captured) {
            journal.add("restore");
            return !restoreFails;
        }
    }

    private static OccupationSnapshot snapshotOf(@Nullable WorksiteRef jobSite, int despawnDelay) {
        CompoundTag offers = new CompoundTag();
        offers.putString("Recipes", "deep-copied");
        return new OccupationSnapshot(VILLAGER, null, 0, jobSite, null, true, offers,
                HistoricalProfessionKind.ID, new ResourceLocation("minecraft", "cleric"),
                true, "cleric_outfit", true, new ResourceLocation("minecraft", "cleric"), despawnDelay);
    }

    private static OccupationRequest station() {
        return OccupationRequest.station(VILLAGER, OccupationSource.STATION_RECRUITMENT, STATION, false, false);
    }

    // --- the happy path --------------------------------------------------------------------------

    @Test
    void aCommittedTransitionRunsEveryStepInOrderAndRollsNothingBack() {
        FakeMutator mutator = new FakeMutator();

        OccupationTransitionResult result = OccupationTransaction.run(mutator, station());

        assertTrue(result.committed());
        assertEquals(OccupationStatus.ACTIVE_BOUND_NOVICE, result.status());
        assertEquals(List.of("snapshot", "reserve:10", "releaseOld", "memories", "profession", "offers",
                "jobSite", "xp", "verify"), mutator.journal);
    }

    @Test
    void anEstablishedRebindKeepsItsMilestoneAndSkipsTheSetter() {
        FakeMutator mutator = new FakeMutator();
        OccupationRequest rebind = new OccupationRequest(VILLAGER, OccupationSource.NATIVE_REBIND, STATION,
                true, true, true, false);

        OccupationTransitionResult result = OccupationTransaction.run(mutator, rebind);

        assertTrue(result.committed());
        assertEquals(OccupationStatus.ACTIVE_BOUND_ESTABLISHED, result.status());
        assertFalse(mutator.journal.contains("profession"),
                "a same-profession rebind must not call MCA's setter or refresh the brain again");
        assertTrue(mutator.journal.contains("adopt"),
                "an adopted ticket is confirmed, never taken a second time");
    }

    // --- failure injection, one step at a time ----------------------------------------------------

    @Test
    void everyFailedMutationRollsBackAndReportsWhy() {
        record Case(Step step, OccupationTransitionReason reason) { }
        List<Case> cases = List.of(
                new Case(Step.PROFESSION, OccupationTransitionReason.VERIFICATION_FAILED),
                new Case(Step.OFFERS, OccupationTransitionReason.MUTATION_FAILED),
                new Case(Step.JOB_SITE, OccupationTransitionReason.MUTATION_FAILED),
                new Case(Step.XP, OccupationTransitionReason.MUTATION_FAILED),
                new Case(Step.VERIFY, OccupationTransitionReason.VERIFICATION_FAILED));

        for (Case each : cases) {
            FakeMutator mutator = new FakeMutator();
            mutator.failAt = each.step();

            OccupationTransitionResult result = OccupationTransaction.run(mutator, station());

            assertTrue(result.rejected(), each.step() + " must not commit");
            assertEquals(each.reason(), result.reason(), each.step().toString());
            assertTrue(mutator.journal.contains("restore"), each.step() + " must roll back");
            assertTrue(mutator.journal.contains("release:10"),
                    each.step() + " must give back the ticket it took");
            assertTrue(mutator.journal.contains("reserve:-30"),
                    each.step() + " must take the old workstation claim back");
        }
    }

    @Test
    void aTicketTakenBySomebodyElseStopsBeforeAnythingIsChanged() {
        FakeMutator mutator = new FakeMutator();
        mutator.failAt = Step.RESERVE;

        OccupationTransitionResult result = OccupationTransaction.run(mutator, station());

        assertEquals(OccupationTransitionReason.WORKSITE_LOST, result.reason());
        assertEquals(List.of("snapshot", "reserve:10"), mutator.journal,
                "a lost race must not release the villager's existing job or touch its profession");
    }

    @Test
    void anAdoptedTicketIsNeverReleasedByARollback() {
        FakeMutator mutator = new FakeMutator();
        mutator.failAt = Step.VERIFY;
        OccupationRequest adopt = OccupationRequest.station(VILLAGER, OccupationSource.NATIVE_REBIND,
                STATION, true, false);

        OccupationTransaction.run(mutator, adopt);

        assertFalse(mutator.journal.contains("release:10"),
                "the villager owned that ticket before this transaction; releasing it would strip a "
                        + "claim the transaction never took");
    }

    @Test
    void aFailedRollbackSuspendsRatherThanPretendingTheVillagerIsClean() {
        FakeMutator mutator = new FakeMutator();
        // Something has to go wrong first for a rollback to happen at all; then the rollback fails too.
        mutator.failAt = Step.VERIFY;
        mutator.restoreFails = true;

        OccupationTransitionResult result = OccupationTransaction.run(mutator, station());

        assertTrue(result.suspended());
        assertEquals(OccupationTransitionReason.ROLLBACK_INCOMPLETE, result.reason());
    }

    @Test
    void anOldClaimThatChangedHandsSuspendsInsteadOfBeingReportedAsRepaired() {
        FakeMutator mutator = new FakeMutator();
        mutator.failAt = Step.XP;
        mutator.oldTicketRecoverable = false;

        OccupationTransitionResult result = OccupationTransaction.run(mutator, station());

        assertTrue(result.suspended(),
                "ownership moved while this ran; the operator needs to know, not be told it is fine");
    }

    // --- refusals before any mutation -------------------------------------------------------------

    @Test
    void anUnreadableVillagerIsRefusedBeforeAnythingIsTouched() {
        FakeMutator mutator = new FakeMutator();
        mutator.snapshot = null;

        OccupationTransitionResult result = OccupationTransaction.run(mutator, station());

        assertEquals(OccupationTransitionReason.NOT_LOADED, result.reason());
        assertEquals(List.of("snapshot"), mutator.journal);
    }

    @Test
    void aTemporaryInnOccupantIsNeverRecruited() {
        FakeMutator mutator = new FakeMutator();
        mutator.snapshot = snapshotOf(null, 400);

        OccupationTransitionResult result = OccupationTransaction.run(mutator, station());

        assertEquals(OccupationTransitionReason.PROTECTED_NPC, result.reason(),
                "MCA discards or resets these on expiry whatever their trading XP says");
    }

    @Test
    void anUnreadablePreviousProfessionIsRefusedBecauseItCouldNotBeRolledBack() {
        FakeMutator mutator = new FakeMutator();
        mutator.snapshot = new OccupationSnapshot(VILLAGER, null, 0, null, null, false, null,
                HistoricalProfessionKind.UNREADABLE, null, false, null, false, null, 0);

        OccupationTransitionResult result = OccupationTransaction.run(mutator, station());

        assertEquals(OccupationTransitionReason.CLASSIFICATION_UNAVAILABLE, result.reason());
        assertEquals(List.of("snapshot"), mutator.journal);
    }

    @Test
    void theSettlementPathRefusesToCreateAStationlessThief() {
        FakeMutator mutator = new FakeMutator();
        OccupationRequest stationless = new OccupationRequest(VILLAGER, OccupationSource.SETTLEMENT_SWEEP,
                null, false, false, false, false);

        OccupationTransitionResult result = OccupationTransaction.run(mutator, stationless);

        assertEquals(OccupationTransitionReason.NO_WORKSITE, result.reason());
        assertTrue(mutator.journal.isEmpty());
    }

    @Test
    void theConfiguredWildExceptionCommitsWithNoStationAtAll() {
        FakeMutator mutator = new FakeMutator();
        mutator.snapshot = snapshotOf(null, 0);

        OccupationTransitionResult result = OccupationTransaction.run(mutator,
                OccupationRequest.unbound(VILLAGER, OccupationSource.WILD, true));

        assertTrue(result.committed());
        assertEquals(OccupationStatus.ESTABLISHED_UNBOUND, result.status());
        assertFalse(mutator.journal.contains("jobSite"));
    }

    @Test
    void aThrowingMutatorIsContainedAndRolledBack() {
        OccupationMutator throwing = new FakeMutator() {
            @Override
            public boolean applyProfession() {
                throw new IllegalStateException("MCA setter exploded half way through");
            }
        };

        OccupationTransitionResult result = OccupationTransaction.run(throwing, station());

        assertFalse(result.committed());
        assertEquals(OccupationTransitionReason.MUTATION_FAILED, result.reason());
    }

    @Test
    void nothingIsPublishedByTheTransactionItself() {
        // The transaction has no way to publish: it returns a result and the caller decides. This is
        // the structural guarantee behind spec §9.3 step 9, and it is asserted by the absence of any
        // collaborator on OccupationMutator that could post an event.
        for (var method : OccupationMutator.class.getMethods()) {
            assertFalse(method.getName().toLowerCase(java.util.Locale.ROOT).contains("publish"));
            assertFalse(method.getName().toLowerCase(java.util.Locale.ROOT).contains("event"));
        }
    }
}
