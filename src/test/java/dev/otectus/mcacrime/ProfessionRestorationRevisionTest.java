package dev.otectus.mcacrime;

import dev.otectus.mcacrime.job.CriminalJob;
import dev.otectus.mcacrime.job.ProfessionPresentationRevision;
import dev.otectus.mcacrime.job.ProfessionPresentationRevision.Decision;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Whether MCA: Crime still owns a villager's visible profession when it comes to give it back.
 *
 * <h2>The bug without this</h2>
 *
 * <p>MCA: Crime labels a fence, remembers they used to be a farmer, and puts the farmer back when the
 * label is no longer warranted. Nothing checks whether anything happened in between. A player who made
 * that villager a librarian an hour ago, or a settlement mod that gave them a trade, has their change
 * silently overwritten by a value remembered from before it — with no message, because a profession
 * revert produces none.
 *
 * <h2>The failure mode of a careless fix</h2>
 *
 * <p>Refusing every revert that cannot be verified is worse than the bug. MCA's profession accessor is
 * one of the things this mod is built to survive losing, and if an unreadable profession blocked the
 * revert, a pack that turned the fence label off would leave every shopkeeper labelled forever with no
 * way back. So the check is positive: it refuses only when it can see a <em>different</em> profession,
 * and {@link #anUnreadableProfessionStillRestores()} is the case that pins that.
 */
class ProfessionRestorationRevisionTest {

    private static final ResourceLocation FENCE = new ResourceLocation("mcacrime", "fence");
    private static final ResourceLocation THIEF = new ResourceLocation("mcacrime", "thief");
    private static final ResourceLocation LIBRARIAN = new ResourceLocation("minecraft", "librarian");

    /** The ordinary path: nobody touched the villager, so the old profession goes back. */
    @Test
    void anUntouchedVillagerIsRestored() {
        Decision decision = ProfessionPresentationRevision.decide(FENCE, FENCE);
        assertSame(Decision.RESTORE, decision);
        assertTrue(decision.restores());
    }

    /** A later writer wins, and the revert is abandoned rather than fought. */
    @Test
    void anInterveningChangeWins() {
        Decision decision = ProfessionPresentationRevision.decide(FENCE, LIBRARIAN);
        assertSame(Decision.SKIP_CHANGED, decision);
        assertFalse(decision.restores());
    }

    /**
     * Two of this mod's own labels are still two different labels.
     *
     * <p>A record that says "fence" against a villager currently wearing {@code mcacrime:thief} is not
     * a villager this fence record may revert: the thief profession is owned by the occupation
     * transaction, and writing over it from the presentation side is the second-writer bug the
     * occupation work removed.
     */
    @Test
    void oneOfThisModsOwnLabelsDoesNotAuthoriseRevertingAnother() {
        assertSame(Decision.SKIP_CHANGED, ProfessionPresentationRevision.decide(FENCE, THIEF));
    }

    /** Cannot read: restore, exactly as this mod behaved before the check existed. */
    @Test
    void anUnreadableProfessionStillRestores() {
        Decision decision = ProfessionPresentationRevision.decide(FENCE, null);
        assertSame(Decision.RESTORE_UNVERIFIED, decision);
        assertTrue(decision.restores(),
                "an unbound MCA accessor must not strand every labelled villager");
    }

    /** Never wrote one: nothing to compare, so nothing new is refused. */
    @Test
    void aJobThatWasNeverPresentedStillRestores() {
        Decision decision = ProfessionPresentationRevision.decide(null, LIBRARIAN);
        assertSame(Decision.RESTORE_UNVERIFIED, decision);
        assertTrue(decision.restores());
    }

    /**
     * The expected value is derived from the job rather than stored beside the record.
     *
     * <p>That is the whole reason this needs no new saved field and no migration, so it is worth a test
     * of its own: the profession a job is presented as has to be a total, constant function of the job.
     */
    @Test
    void theExpectedProfessionIsAPureFunctionOfTheJob() {
        assumeTrue(professionsLoadable(),
                "Forge registries are unavailable in this environment, so the profession ids cannot be "
                        + "read; the null-job case below covers what can be checked without them.");
        assertNull(ProfessionPresentationRevision.expectedFor(CriminalJob.NONE),
                "an unlabelled job writes no profession, so there is nothing to expect");
        for (CriminalJob job : CriminalJob.values()) {
            assertSame(ProfessionPresentationRevision.expectedFor(job),
                    ProfessionPresentationRevision.expectedFor(job),
                    "asking twice must give the same answer or the check would be a coin toss");
        }
        assertSame(Decision.RESTORE, ProfessionPresentationRevision.decide(
                ProfessionPresentationRevision.expectedFor(CriminalJob.FENCE),
                ProfessionPresentationRevision.expectedFor(CriminalJob.FENCE)));
    }

    /**
     * No job, no expectation — and answered before anything is loaded.
     *
     * <p>Kept separate from the case above because it is the one part of {@code expectedFor} that needs
     * no registry, and because it is the path {@code revertPresentation} takes for a record with no job
     * at all: the revert then falls back to the unverified branch rather than refusing.
     */
    @Test
    void aNullJobExpectsNothingAndStillRestores() {
        assertNull(ProfessionPresentationRevision.expectedFor(null));
        assertSame(Decision.RESTORE_UNVERIFIED,
                ProfessionPresentationRevision.decide(ProfessionPresentationRevision.expectedFor(null),
                        LIBRARIAN));
    }

    private static boolean professionsLoadable() {
        try {
            ProfessionPresentationRevision.expectedFor(CriminalJob.FENCE);
            return true;
        } catch (Throwable unavailable) {
            return false;
        }
    }
}
