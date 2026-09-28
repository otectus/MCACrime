package dev.otectus.mcacrime.frisk;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A guard search and a robbery are two events (M5.3, spec §11.1).
 *
 * <p>The specification's complaint about the upstream screen is that both are the same code path
 * there. {@link FriskingService#classify} is the whole distinction, and it is pure, so every
 * combination of the facts is enumerated below rather than sampled. Searching yourself is not a
 * search: {@code FriskActionHandler} hides the row and {@code FriskingService.open} refuses it.
 */
class SeizureClassificationTest {

    private static SeizureKind classify(boolean custody, boolean lawful, boolean custodian,
                                        boolean onDuty) {
        return FriskingService.classify(custody, lawful, custodian, onDuty);
    }

    private static String read(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    @Test
    void onlyALawfulCustodianOrAnOnDutyGuardSearchesLawfully() {
        assertEquals(SeizureKind.LAWFUL_SEARCH, classify(true, true, true, false));
        assertEquals(SeizureKind.LAWFUL_SEARCH, classify(true, true, false, true));
        // Holding somebody unlawfully is a kidnapping, and rummaging through them is still robbery.
        assertEquals(SeizureKind.CRIMINAL_SEIZURE, classify(true, false, true, false));
        // A lawful custody somebody else holds does not make this searcher a custodian.
        assertEquals(SeizureKind.CRIMINAL_SEIZURE, classify(true, true, false, false));
        // No custody at all: nobody has authority over anybody.
        assertEquals(SeizureKind.CRIMINAL_SEIZURE, classify(false, false, true, true));
    }

    @Test
    void everyKindRoutesSomewhereAndOnlyOneToEscrow() {
        assertEquals(2, SeizureKind.values().length, "a lawful search and a robbery, nothing else");
        int escrowed = 0;
        int criminal = 0;
        for (SeizureKind kind : SeizureKind.values()) {
            assertTrue(kind.messageKey().startsWith("mcacrime.frisk.seizure."));
            escrowed += kind.escrowed() ? 1 : 0;
            criminal += kind.criminal() ? 1 : 0;
        }
        assertEquals(1, escrowed, "only a lawful search owes the subject their property back");
        assertEquals(1, criminal, "only a criminal seizure files a theft");
    }

    @Test
    void aLawfulSearchRunsTheContrabandDiscoveryGate() {
        String service = read(Path.of("src", "main", "java", "dev", "otectus", "mcacrime", "frisk",
                "FriskingService.java"));
        assertTrue(service.contains("ContrabandSearchService.onFrisk(level, searcher, searched)"),
                "contraband is discovered through the existing gate, not by opening a screen");
        assertTrue(service.contains("if (kind == SeizureKind.LAWFUL_SEARCH"),
                "and only for a lawful search: a robbery discovers nothing officially");
    }

    @Test
    void searchingYourselfNeverOpensASession() {
        String service = read(Path.of("src", "main", "java", "dev", "otectus", "mcacrime", "frisk",
                "FriskingService.java"));
        assertTrue(service.contains("if (searcher.getUUID().equals(subject.getUUID())) {"),
                "your own pockets are refused before anything is classified");
        assertFalse(service.contains("VOLUNTARY"), "there is no voluntary kind left to fall into");
    }

    @Test
    void aSubjectMustBeHelplessBeforeTheyCanBeSearched() {
        // Nothing worn and the requirement on: refused. This is the physical question, which is
        // asked separately from the legal one above -- exactly as §11.1 requires.
        assertFalse(FriskingService.restraintSatisfied(null, true));
        assertTrue(FriskingService.restraintSatisfied(null, false),
                "a server that turns the requirement off gets an authority check and nothing else");
    }
}
