package dev.otectus.mcacrime;

import dev.otectus.mcacrime.api.model.CrimeCommunityKey;
import dev.otectus.mcacrime.api.model.CrimeRecordView;
import dev.otectus.mcacrime.compat.CrimeIncidentMapping;
import dev.otectus.mcacrime.crime.type.CrimeIds;
import dev.otectus.mcacrime.integration.SupersedePolicy;
import dev.otectus.mcacrime.ledger.Resolution;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * When a killing absorbs the assault that preceded it.
 *
 * <p>Why it matters: while MCA: Crime holds the detection authority, the assault-to-killing fold is
 * ours to ask for. MCA: Reputation's own detector records one encounter as one incident, so if we did
 * not ask, a player who beat a villager and then killed them would pay for both — a heavier penalty
 * than the mod we took the deed from would ever have applied. Every rule below is a reason <em>not</em>
 * to fold, because a wrong fold quietly deletes a separate crime.
 */
class SupersedePolicyTest {

    private static final long WINDOW = 1200L;
    private static final CrimeCommunityKey VILLAGE =
            CrimeCommunityKey.of(ResourceLocation.fromNamespaceAndPath("minecraft", "overworld"), 0).orElseThrow();
    private static final CrimeCommunityKey OTHER_VILLAGE =
            CrimeCommunityKey.of(ResourceLocation.fromNamespaceAndPath("minecraft", "overworld"), 1).orElseThrow();

    private static final UUID OFFENDER = UUID.randomUUID();
    private static final UUID VICTIM = UUID.randomUUID();

    private static CrimeRecordView view(ResourceLocation crime, UUID victim, long committed,
                                        CrimeCommunityKey community, UUID linkedIncident,
                                        Resolution resolution) {
        return new CrimeRecordView(UUID.randomUUID(), OFFENDER, Optional.ofNullable(victim), crime,
                Optional.ofNullable(community), Set.of(), true, committed, 10L, -10L, 0L, 0L,
                resolution, 0L, Optional.ofNullable(linkedIncident), Map.of());
    }

    private static CrimeRecordView assault(UUID victim, long committed, UUID linkedIncident) {
        return view(CrimeIds.HARM_VILLAGER, victim, committed, VILLAGE, linkedIncident,
                Resolution.UNRESOLVED);
    }

    private static CrimeRecordView killing(long committed) {
        return view(CrimeIds.KILL_VILLAGER, VICTIM, committed, VILLAGE, null, Resolution.UNRESOLVED);
    }

    // ------------------------------------------------------------------ the fold

    @Test
    void aKillingAbsorbsTheLinkedAssaultOnTheSameVictim() {
        UUID incident = UUID.randomUUID();

        assertEquals(Optional.of(incident), SupersedePolicy.precursorFor(
                CrimeIncidentMapping.VILLAGER_KILLED, killing(1000L),
                List.of(assault(VICTIM, 900L, incident)), WINDOW));
    }

    /** A mugging murder is a killing with a motive attached; the precursor it absorbs is the same. */
    @Test
    void aMuggingMurderFoldsTheSameWay() {
        UUID incident = UUID.randomUUID();

        assertTrue(SupersedePolicy.isFatal(CrimeIncidentMapping.MUGGING_MURDER));
        assertEquals(Optional.of(incident), SupersedePolicy.precursorFor(
                CrimeIncidentMapping.MUGGING_MURDER,
                view(CrimeIds.MUGGING_MURDER, VICTIM, 1000L, VILLAGE, null, Resolution.UNRESOLVED),
                List.of(assault(VICTIM, 950L, incident)), WINDOW));
    }

    /** The most recent eligible assault, deterministically, so a replay picks the same one. */
    @Test
    void theNewestEligibleAssaultIsChosen() {
        UUID older = UUID.randomUUID();
        UUID newer = UUID.randomUUID();

        assertEquals(Optional.of(newer), SupersedePolicy.precursorFor(
                CrimeIncidentMapping.VILLAGER_KILLED, killing(1000L),
                List.of(assault(VICTIM, 500L, older), assault(VICTIM, 900L, newer)), WINDOW));
    }

    // ------------------------------------------------------------------ the refusals

    @Test
    void anAssaultOnSomebodyElseIsNotPartOfThisKilling() {
        assertTrue(SupersedePolicy.precursorFor(CrimeIncidentMapping.VILLAGER_KILLED, killing(1000L),
                List.of(assault(UUID.randomUUID(), 900L, UUID.randomUUID())), WINDOW).isEmpty());
    }

    @Test
    void anAssaultInAnotherVillageIsADifferentLedger() {
        assertTrue(SupersedePolicy.precursorFor(CrimeIncidentMapping.VILLAGER_KILLED, killing(1000L),
                List.of(view(CrimeIds.HARM_VILLAGER, VICTIM, 900L, OTHER_VILLAGE, UUID.randomUUID(),
                        Resolution.UNRESOLVED)), WINDOW).isEmpty());
    }

    /** No link yet means there is nothing on the companion's side to absorb. */
    @Test
    void anUndeliveredAssaultIsNotAPrecursor() {
        assertTrue(SupersedePolicy.precursorFor(CrimeIncidentMapping.VILLAGER_KILLED, killing(1000L),
                List.of(assault(VICTIM, 900L, null)), WINDOW).isEmpty());
    }

    /** A fine already paid for the assault is an atonement the village accepted; folding would erase it. */
    @Test
    void aSettledAssaultIsLeftAlone() {
        assertTrue(SupersedePolicy.precursorFor(CrimeIncidentMapping.VILLAGER_KILLED, killing(1000L),
                List.of(view(CrimeIds.HARM_VILLAGER, VICTIM, 900L, VILLAGE, UUID.randomUUID(),
                        Resolution.FINED)), WINDOW).isEmpty());
    }

    @Test
    void anAssaultOlderThanTheWindowIsASeparateEncounter() {
        assertTrue(SupersedePolicy.precursorFor(CrimeIncidentMapping.VILLAGER_KILLED, killing(10_000L),
                List.of(assault(VICTIM, 900L, UUID.randomUUID())), WINDOW).isEmpty());
    }

    /** A case committed after the killing cannot be its precursor, however small the gap. */
    @Test
    void aLaterAssaultIsNotAPrecursor() {
        assertTrue(SupersedePolicy.precursorFor(CrimeIncidentMapping.VILLAGER_KILLED, killing(1000L),
                List.of(assault(VICTIM, 1100L, UUID.randomUUID())), WINDOW).isEmpty());
    }

    @Test
    void aZeroWindowSwitchesTheFoldOff() {
        assertTrue(SupersedePolicy.precursorFor(CrimeIncidentMapping.VILLAGER_KILLED, killing(1000L),
                List.of(assault(VICTIM, 900L, UUID.randomUUID())), 0L).isEmpty());
    }

    /** Only a fatal outcome folds. A theft or a guard assault stands on its own. */
    @Test
    void aNonFatalIncidentNeverFolds() {
        assertTrue(SupersedePolicy.precursorFor(CrimeIncidentMapping.THEFT,
                view(CrimeIds.THEFT, VICTIM, 1000L, VILLAGE, null, Resolution.UNRESOLVED),
                List.of(assault(VICTIM, 900L, UUID.randomUUID())), WINDOW).isEmpty());
        assertTrue(SupersedePolicy.precursorFor(CrimeIncidentMapping.GUARD_ASSAULTED,
                view(CrimeIds.ASSAULT_GUARD, VICTIM, 1000L, VILLAGE, null, Resolution.UNRESOLVED),
                List.of(assault(VICTIM, 900L, UUID.randomUUID())), WINDOW).isEmpty());
    }

    @Test
    void aKillingWithNoKnownVictimHasNothingToFold() {
        assertTrue(SupersedePolicy.precursorFor(CrimeIncidentMapping.VILLAGER_KILLED,
                view(CrimeIds.KILL_VILLAGER, null, 1000L, VILLAGE, null, Resolution.UNRESOLVED),
                List.of(assault(VICTIM, 900L, UUID.randomUUID())), WINDOW).isEmpty());
    }

    @Test
    void nullsAndEmptyHistoriesAreHandled() {
        assertTrue(SupersedePolicy.precursorFor(null, killing(1000L), List.of(), WINDOW).isEmpty());
        assertTrue(SupersedePolicy.precursorFor(CrimeIncidentMapping.VILLAGER_KILLED, null, List.of(),
                WINDOW).isEmpty());
        assertTrue(SupersedePolicy.precursorFor(CrimeIncidentMapping.VILLAGER_KILLED, killing(1000L),
                null, WINDOW).isEmpty());
        assertTrue(SupersedePolicy.precursorFor(CrimeIncidentMapping.VILLAGER_KILLED, killing(1000L),
                List.of(), WINDOW).isEmpty());
    }

    /** The successor must not absorb itself if it turns up in its own offender history. */
    @Test
    void aCaseIsNeverItsOwnPrecursor() {
        CrimeRecordView fatal = view(CrimeIds.KILL_VILLAGER, VICTIM, 1000L, VILLAGE,
                UUID.randomUUID(), Resolution.UNRESOLVED);

        assertTrue(SupersedePolicy.precursorFor(CrimeIncidentMapping.VILLAGER_KILLED, fatal,
                List.of(fatal), WINDOW).isEmpty());
    }
}
