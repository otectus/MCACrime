package dev.otectus.mcacrime;

import dev.otectus.mcacrime.compat.EpicFightCompat;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Epic Fight seam with neither mod present — which is also the case where a diagnostic is most
 * dangerous, because nothing about a missing mod may throw.
 *
 * <p>The JUnit runner boots a mod list with only this mod in it, and a presence check has to survive
 * both that and no mod list at all: a diagnostic that only works inside a running game is a diagnostic
 * nobody can trust when the game is the thing that is broken.
 */
class EpicFightCompatTest {

    @BeforeEach
    void clearCache() {
        EpicFightCompat.reset();
    }

    @Test
    void anAbsentModIsAbsentRatherThanAnException() {
        assertFalse(EpicFightCompat.isEpicFightLoaded());
        assertFalse(EpicFightCompat.isMceaLoaded());
        assertFalse(EpicFightCompat.isEfmcaLoaded());
        assertFalse(EpicFightCompat.isMcaefLoaded());
        for (String id : List.of(EpicFightCompat.EPIC_FIGHT_ID, EpicFightCompat.MCEA_ID,
                EpicFightCompat.EFMCA_ID, EpicFightCompat.MCAEF_ID)) {
            assertTrue(EpicFightCompat.modVersion(id).isEmpty(), id);
        }
    }

    /** The three ids are distinct, so a rename here cannot quietly stop looking for one of them. */
    @Test
    void allThreeCompanionIdsAreCheckedAndNoneIsAnAlias() {
        assertEquals("mcea", EpicFightCompat.MCEA_ID);
        assertEquals("efmca", EpicFightCompat.EFMCA_ID);
        assertEquals("mcaefcompat", EpicFightCompat.MCAEF_ID);
    }

    @Test
    void withNeitherPatchPlayerDamageIsNotReportedAsBlocked() {
        assertFalse(EpicFightCompat.playerDamageToVillagersBlocked());
    }

    /** An absent efmca has no readable friendly-fire flag, and asking for one may not throw. */
    @Test
    void theFriendlyFireProbeIsEmptyRatherThanAnExceptionWhenEfmcaIsAbsent() {
        assertTrue(EpicFightCompat.efmcaFriendlyFire().isEmpty());
    }

    @Test
    void theDiagnosticNeverThrowsAndReportsTheHealthyVerdict() {
        List<String> lines = EpicFightCompat.diagnosticLines();
        assertEquals(5, lines.size(), "four mod lines plus the damage verdict; no Epic Fight note");
        assertTrue(lines.stream().anyMatch(l -> l.equals("Player damage to MCA villagers: OK")),
                "with both patches absent the verdict is OK: " + lines);
        assertTrue(lines.stream().anyMatch(l -> l.startsWith("mcea (MC-Epicly-A): installed=false")), lines.toString());
        assertTrue(lines.stream().anyMatch(l -> l.startsWith("efmca (EpicFight-MCA Patch): installed=false")),
                lines.toString());
        assertTrue(lines.stream().anyMatch(l -> l.startsWith("mcaefcompat (MCA Skin x Epic Fight): installed=false")),
                lines.toString());
        assertTrue(lines.stream().anyMatch(l -> l.contains("friendlyFire=no such option")),
                "the 1.21.1 patch has no friendly-fire option to report: " + lines);
        assertTrue(lines.stream().anyMatch(l -> l.contains("friendlyFire=unreadable")),
                "an absent efmca reports its flag as unreadable: " + lines);
        assertTrue(lines.stream().noneMatch(l -> l.startsWith("Battle-mode right-click")),
                "the battle-mode note belongs to an installed Epic Fight only: " + lines);
    }
}
