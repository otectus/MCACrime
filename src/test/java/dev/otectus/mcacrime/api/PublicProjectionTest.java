package dev.otectus.mcacrime.api;

import dev.otectus.mcacrime.api.model.CrimeCommunityKey;
import dev.otectus.mcacrime.api.model.CrimePublicView;
import dev.otectus.mcacrime.api.model.CrimeRecordView;
import dev.otectus.mcacrime.crime.Band;
import dev.otectus.mcacrime.ledger.Resolution;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The line between what happened and what a village is allowed to know.
 *
 * <h2>Why this is a test and not a code review comment</h2>
 *
 * <p>Every one of these assertions is a thing that reads as working when it is broken. A village that
 * reacts to an unwitnessed burglary looks like a strict village. A village that knows about a killing
 * in another settlement looks like well-connected gossip. A guard who arrests on an unreported sighting
 * looks attentive. None of them announces itself as a privacy failure, and all of them are the same
 * failure: the projection reading the ledger instead of the knowledge.
 *
 * <p>So the case that gets the most attention below is the one with nothing to see —
 * {@link #anUnwitnessedCrimeIsInvisibleToEveryCommunity()} — because the ledger entry for it is
 * complete, real and legally binding, and the only thing that must never happen is somebody acting on
 * it.
 */
class PublicProjectionTest {

    private static final CrimeCommunityKey RIVERSIDE =
            new CrimeCommunityKey(ResourceLocation.fromNamespaceAndPath("minecraft", "overworld"), 1);
    private static final CrimeCommunityKey HILLTOP =
            new CrimeCommunityKey(ResourceLocation.fromNamespaceAndPath("minecraft", "overworld"), 2);

    private static final UUID OFFENDER = UUID.nameUUIDFromBytes("offender".getBytes());

    /** Nothing has been reported anywhere. */
    private static final Predicate<UUID> NOTHING_REPORTED = id -> false;

    private static CrimeRecordView caseOf(String seed, CrimeCommunityKey community, boolean witnessed,
                                          Resolution resolution, long gameTime, Map<String, String> context) {
        return new CrimeRecordView(
                UUID.nameUUIDFromBytes(seed.getBytes()),
                OFFENDER,
                Optional.empty(),
                ResourceLocation.fromNamespaceAndPath("mcacrime", "theft"),
                Optional.ofNullable(community),
                witnessed ? Set.of(UUID.nameUUIDFromBytes((seed + "-witness").getBytes())) : Set.of(),
                witnessed,
                gameTime,
                10L, -5L, 12L, 0L,
                resolution,
                0L,
                Optional.empty(),
                context);
    }

    private static CrimeRecordView caseOf(String seed, CrimeCommunityKey community, boolean witnessed) {
        return caseOf(seed, community, witnessed, Resolution.UNRESOLVED, 100L, Map.of());
    }

    /**
     * The case the projection exists for.
     *
     * <p>A real crime, a real community, real Heat, and nobody saw it. It stays in the ledger and it
     * stays out of every public view — including the view for the community it happened in, with
     * observations switched off, which is the most permissive configuration there is.
     */
    @Test
    void anUnwitnessedCrimeIsInvisibleToEveryCommunity() {
        CrimeRecordView unseen = caseOf("unseen", RIVERSIDE, false);

        for (boolean observations : new boolean[] {false, true}) {
            for (CrimeCommunityKey observer : new CrimeCommunityKey[] {RIVERSIDE, HILLTOP}) {
                assertFalse(CrimePublicView.isPublic(unseen, observer, observations, id -> true),
                        "an unwitnessed crime must never be public, whatever the settings say "
                                + "(observations=" + observations + ", observer=" + observer.asString() + ")");
            }
        }

        CrimePublicView view = CrimePublicView.of(RIVERSIDE, OFFENDER, Band.GREY, false, 0, 0L,
                List.of(unseen), false, id -> true);
        assertEquals(0, view.publicIncidents());
        assertTrue(view.recent().isEmpty());
        assertFalse(view.known(), "a community that knows nothing must say so");
    }

    /** Another village's crime is that village's business. */
    @Test
    void aCrimeInAnotherCommunityDoesNotTravel() {
        CrimeRecordView elsewhere = caseOf("elsewhere", HILLTOP, true);
        assertFalse(CrimePublicView.isPublic(elsewhere, RIVERSIDE, false, NOTHING_REPORTED));
        assertTrue(CrimePublicView.isPublic(elsewhere, HILLTOP, false, NOTHING_REPORTED));
    }

    /** A crime in the wilderness belongs to nobody. */
    @Test
    void aCrimeWithNoCommunityIsNobodysBusiness() {
        CrimeRecordView wilderness = caseOf("wilderness", null, true);
        assertFalse(CrimePublicView.isPublic(wilderness, RIVERSIDE, false, id -> true));
        assertFalse(CrimePublicView.isPublic(wilderness, HILLTOP, false, id -> true));
    }

    /**
     * Being seen is not being reported.
     *
     * <p>With the observation layer running, a witness who has not yet reached an authority is a
     * villager with a memory, not a public fact. This is what keeps the walk back to the village from
     * being instantaneous and what stops every bystander knowing the moment anybody does.
     */
    @Test
    void withObservationsOnAWitnessedCrimeWaitsForTheReport() {
        CrimeRecordView seen = caseOf("seen", RIVERSIDE, true);
        assertFalse(CrimePublicView.isPublic(seen, RIVERSIDE, true, NOTHING_REPORTED),
                "seen but not reported is not yet public knowledge");
        assertTrue(CrimePublicView.isPublic(seen, RIVERSIDE, true, id -> id.equals(seen.id())),
                "once an accepted report names it, the community knows");
        assertTrue(CrimePublicView.isPublic(seen, RIVERSIDE, false, NOTHING_REPORTED),
                "with no reporting layer at all, witnessed is the whole rule");
    }

    /**
     * A prisoner missing from a cell needs no witness.
     *
     * <p>Jailbreaks and operator commands carry no witness list, so the witness test alone would hide
     * exactly the two things an authority already knows first-hand. Same carve-out, same two detection
     * values, as the civic incident filing uses.
     */
    @Test
    void anAuthorityKnowsItsOwnJailbreak() {
        CrimeRecordView jailbreak = caseOf("jailbreak", RIVERSIDE, false, Resolution.UNRESOLVED, 100L,
                Map.of("detection", "jailbreak"));
        assertTrue(CrimePublicView.isPublic(jailbreak, RIVERSIDE, true, NOTHING_REPORTED));

        CrimeRecordView byCommand = caseOf("command", RIVERSIDE, false, Resolution.UNRESOLVED, 100L,
                Map.of("detection", "command"));
        assertTrue(CrimePublicView.isPublic(byCommand, RIVERSIDE, true, NOTHING_REPORTED));
    }

    /** Counts, ordering and the cap: the projection is a summary, not a second ledger. */
    @Test
    void theViewIsNewestFirstAndCapped() {
        List<CrimeRecordView> cases = new java.util.ArrayList<>();
        for (int i = 0; i < CrimePublicView.MAX_RECENT + 5; i++) {
            cases.add(caseOf("case-" + i, RIVERSIDE, true, Resolution.UNRESOLVED, i, Map.of()));
        }
        cases.add(caseOf("settled", RIVERSIDE, true, Resolution.FINED, 500L, Map.of()));

        CrimePublicView view = CrimePublicView.of(RIVERSIDE, OFFENDER, Band.RED, true, -40, 120L,
                cases, false, NOTHING_REPORTED);

        assertEquals(cases.size(), view.publicIncidents(), "the magnitude is not truncated by the cap");
        assertEquals(cases.size() - 1, view.openIncidents(), "a settled case is not an open one");
        assertEquals(CrimePublicView.MAX_RECENT, view.recent().size());
        assertEquals(500L, view.recent().get(0).gameTime(), "newest first");
        assertTrue(view.known());
        assertEquals(120L, view.openBountyAmount());
        assertEquals(-40, view.standing());
    }

    /** No witness ids, no victim, no heat: a public incident is only what can be said out loud. */
    @Test
    void aPublicIncidentCarriesNoPrivateDetail() {
        CrimePublicView view = CrimePublicView.of(RIVERSIDE, OFFENDER, Band.GREY, false, 0, 0L,
                List.of(caseOf("seen", RIVERSIDE, true)), false, NOTHING_REPORTED);
        assertEquals(1, view.recent().size());
        CrimePublicView.PublicIncident incident = view.recent().get(0);
        // The record's own accessors are the exhaustive list of what a PublicIncident could have
        // carried; these four are what it does carry, and the test fails if a fifth is ever added
        // without a decision being made about it.
        assertEquals(4, CrimePublicView.PublicIncident.class.getRecordComponents().length,
                "a public incident gained a field; confirm it is something a village may say out loud");
        assertTrue(incident.open());
    }

    /** An empty view is a real answer, not a missing one. */
    @Test
    void anEmptyViewIsWellFormed() {
        CrimePublicView empty = CrimePublicView.empty(RIVERSIDE, OFFENDER);
        assertFalse(empty.known());
        assertEquals(0, empty.publicIncidents());
        assertEquals(Band.GREY, empty.band());
    }
}
