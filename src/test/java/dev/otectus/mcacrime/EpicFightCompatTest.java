package dev.otectus.mcacrime;

import dev.otectus.mcacrime.compat.EpicFightCompat;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Epic Fight seam with none of the three mods present — which is also the case where a diagnostic
 * is most dangerous, because nothing about a missing mod may throw.
 *
 * <p>There is no mod list at all under JUnit, so every presence check here exercises the
 * {@code ModList.get()} failure path, and that is deliberate: a diagnostic that only works inside a
 * running game is a diagnostic nobody can trust when the game is the thing that is broken.
 */
class EpicFightCompatTest {

    @BeforeEach
    void clearCache() {
        EpicFightCompat.reset();
    }

    @Test
    void aMissingModListMeansAbsentRatherThanAnException() {
        assertFalse(EpicFightCompat.isEpicFightLoaded());
        assertFalse(EpicFightCompat.isEfmcaLoaded());
        assertFalse(EpicFightCompat.isMcaefLoaded());
        assertTrue(EpicFightCompat.modVersion("efmca").isEmpty());
    }

    @Test
    void friendlyFireIsUnreadableWhenTheClassIsNotOnTheClasspath() {
        assertTrue(EpicFightCompat.efmcaFriendlyFire().isEmpty(),
                "net.forixaim.mcea.Config is not a dependency and must never be required to be");
    }

    @Test
    void withoutEfmcaPlayerDamageIsNotReportedAsBlocked() {
        assertFalse(EpicFightCompat.playerDamageToVillagersBlocked());
    }

    @Test
    void theDiagnosticNeverThrowsAndReportsTheHealthyVerdict() {
        List<String> lines = EpicFightCompat.diagnosticLines();
        assertEquals(4, lines.size(), "three mod lines plus the damage verdict; no Epic Fight note");
        assertTrue(lines.stream().anyMatch(l -> l.equals("Player damage to MCA villagers: OK")),
                "with efmca absent the verdict is OK: " + lines);
        assertTrue(lines.stream().anyMatch(l -> l.contains("friendlyFire=unreadable")),
                "an absent efmca has no readable friendlyFire: " + lines);
        assertTrue(lines.stream().noneMatch(l -> l.startsWith("Battle-mode right-click")),
                "the battle-mode note belongs to an installed Epic Fight only: " + lines);
    }
}
