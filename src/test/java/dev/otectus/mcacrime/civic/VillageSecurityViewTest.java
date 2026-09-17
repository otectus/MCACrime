package dev.otectus.mcacrime.civic;

import dev.otectus.mcacrime.api.model.CrimeCommunityKey;
import dev.otectus.mcacrime.api.model.CrimePublicView;
import dev.otectus.mcacrime.ledger.Resolution;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The derived safety score: capped at both ends, decaying, and never a permanent mark.
 *
 * <h2>What would go wrong without these</h2>
 *
 * <p>An uncapped crime counter has exactly one behaviour: it goes down forever. A village where
 * something happened last month would be permanently marked, every petty theft would weigh as much as
 * the one before it, and the number would stop tracking anything a player could change — which makes it
 * useless as a thing to show them and actively harmful as a thing to make decisions from. So the cases
 * below are about recovery as much as about arithmetic: a village that gets its guards back, settles its
 * cases and waits out the window comes back up.
 *
 * <p>Village spirit is not an input and there is no test for its influence, because there is no
 * influence to test. That is the reference plan's rule (§11.4) and the constructor signature is where it
 * is enforced: there is nowhere to pass one in.
 */
class VillageSecurityViewTest {

    private static final CrimeCommunityKey RIVERSIDE =
            new CrimeCommunityKey(new ResourceLocation("minecraft", "overworld"), 7);

    private static CrimePublicView.PublicIncident incident(long at, boolean open) {
        return new CrimePublicView.PublicIncident(UUID.randomUUID(),
                new ResourceLocation("mcacrime", "theft"), at,
                open ? Resolution.UNRESOLVED : Resolution.FINED);
    }

    private static List<CrimePublicView.PublicIncident> incidents(int count, long at, boolean open) {
        List<CrimePublicView.PublicIncident> list = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            list.add(incident(at, open));
        }
        return list;
    }

    /** A quiet village with cover and a cell reads as secure. */
    @Test
    void aQuietCoveredVillageIsSecure() {
        VillageSecurityView view = VillageSecurityView.compute(RIVERSIDE, 1000L, 0L, List.of(),
                4, 3, 0, 1, 1, 1);
        assertTrue(view.score() >= 75, "score was " + view.score());
        assertSame(VillageSecurityView.Rating.SECURE, view.rating());
        assertTrue(view.canHold());
        assertTrue(view.canCare());
    }

    /** No law, no cell, and open cases: the bottom of the scale, and it says why. */
    @Test
    void anUnpolicedVillageWithOpenCasesIsLawless() {
        VillageSecurityView view = VillageSecurityView.compute(RIVERSIDE, 1000L, 0L,
                incidents(10, 1000L, true), 0, 0, 3, 0, 0, 0);
        assertSame(VillageSecurityView.Rating.LAWLESS, view.rating());
        assertFalse(view.canHold());
        assertTrue(view.notes().stream().anyMatch(note -> note.contains("no law")), view.notes().toString());
        assertTrue(view.notes().stream().anyMatch(note -> note.contains("jail cell")), view.notes().toString());
    }

    /**
     * Crime cannot drive the score to nothing on its own.
     *
     * <p>A hundred thefts and one theft differ, but not by a hundred times: past the cap the village is
     * already as rattled as the model lets it be, and the difference between "bad" and "unimaginable"
     * is not something a score out of a hundred can usefully carry.
     */
    @Test
    void incidentPressureIsCapped() {
        VillageSecurityView few = VillageSecurityView.compute(RIVERSIDE, 1000L, 0L,
                incidents(6, 1000L, true), 0, 0, 0, 0, 0, 0);
        VillageSecurityView many = VillageSecurityView.compute(RIVERSIDE, 1000L, 0L,
                incidents(200, 1000L, true), 0, 0, 0, 0, 0, 0);
        assertEquals(few.score(), many.score(), "past the cap, more crime cannot mean a lower score");
        assertTrue(many.score() >= VillageSecurityView.MIN_SCORE);
        assertEquals(200, many.knownIncidents(), "the magnitude is still reported honestly");
    }

    /** A garrison of twenty is not twice as safe as one of ten. */
    @Test
    void guardCreditIsCapped() {
        VillageSecurityView ten = VillageSecurityView.compute(RIVERSIDE, 1000L, 0L, List.of(),
                10, 10, 0, 0, 0, 0);
        VillageSecurityView fifty = VillageSecurityView.compute(RIVERSIDE, 1000L, 0L, List.of(),
                50, 50, 0, 0, 0, 0);
        assertEquals(ten.score(), fifty.score());
        assertTrue(fifty.score() <= VillageSecurityView.MAX_SCORE);
    }

    /**
     * A village recovers.
     *
     * <p>The same incidents, read a window later, weigh nothing at all — and stop being counted as
     * evidence, not merely discounted. Without this the score is a permanent record of the worst night
     * a settlement ever had.
     */
    @Test
    void oldIncidentsStopCounting() {
        long window = VillageSecurityView.DEFAULT_WINDOW_TICKS;
        List<CrimePublicView.PublicIncident> old = incidents(8, 0L, true);

        VillageSecurityView fresh = VillageSecurityView.compute(RIVERSIDE, 0L, window, old,
                2, 2, 0, 1, 0, 0);
        VillageSecurityView later = VillageSecurityView.compute(RIVERSIDE, window, window, old,
                2, 2, 0, 1, 0, 0);

        assertTrue(later.score() > fresh.score(), fresh.score() + " -> " + later.score());
        assertEquals(0, later.knownIncidents(), "an incident outside the window is not evidence");
        assertEquals(0, later.openIncidents());
    }

    /** Decay runs from 1 to 0 across the window, and a future stamp is not treated as safety. */
    @Test
    void decayIsLinearAndDefensive() {
        long window = 1000L;
        assertEquals(1.0D, VillageSecurityView.decay(500L, 500L, window), 1e-9);
        assertEquals(0.5D, VillageSecurityView.decay(0L, 500L, window), 1e-9);
        assertEquals(0.0D, VillageSecurityView.decay(0L, 1000L, window), 1e-9);
        assertEquals(0.0D, VillageSecurityView.decay(0L, 5000L, window), 1e-9);
        assertEquals(1.0D, VillageSecurityView.decay(900L, 100L, window), 1e-9,
                "a replayed or clock-skewed record is not evidence that the village is safe");
    }

    /** The score never leaves its bounds, whatever is thrown at it. */
    @Test
    void theScoreStaysInsideItsBounds() {
        VillageSecurityView worst = VillageSecurityView.compute(RIVERSIDE, 1000L, 0L,
                incidents(500, 1000L, true), 0, 0, 1000, 0, 0, 0);
        assertTrue(worst.score() >= VillageSecurityView.MIN_SCORE);
        assertTrue(worst.score() <= VillageSecurityView.MAX_SCORE);

        VillageSecurityView best = VillageSecurityView.compute(RIVERSIDE, 1000L, 0L, List.of(),
                1000, 1000, 0, 1000, 1000, 1000);
        assertTrue(best.score() <= VillageSecurityView.MAX_SCORE);
        assertSame(VillageSecurityView.Rating.SECURE, best.rating());
    }

    /** Negative inputs from a caller that got its arithmetic wrong cannot produce a negative report. */
    @Test
    void negativeInputsAreNeverReported() {
        VillageSecurityView view = VillageSecurityView.compute(RIVERSIDE, 0L, 0L, null,
                -5, -5, -5, -5, -5, -5);
        assertEquals(0, view.guards());
        assertEquals(0, view.cells());
        assertEquals(0, view.knownIncidents());
        assertTrue(view.score() >= VillageSecurityView.MIN_SCORE);
    }

    /** The rating thresholds live on the enum, so nothing else invents its own. */
    @Test
    void ratingsFollowTheScore() {
        assertSame(VillageSecurityView.Rating.SECURE, VillageSecurityView.Rating.of(100));
        assertSame(VillageSecurityView.Rating.SECURE, VillageSecurityView.Rating.of(75));
        assertSame(VillageSecurityView.Rating.STEADY, VillageSecurityView.Rating.of(74));
        assertSame(VillageSecurityView.Rating.STEADY, VillageSecurityView.Rating.of(50));
        assertSame(VillageSecurityView.Rating.STRAINED, VillageSecurityView.Rating.of(49));
        assertSame(VillageSecurityView.Rating.STRAINED, VillageSecurityView.Rating.of(25));
        assertSame(VillageSecurityView.Rating.LAWLESS, VillageSecurityView.Rating.of(24));
        assertSame(VillageSecurityView.Rating.LAWLESS, VillageSecurityView.Rating.of(0));
    }
}
