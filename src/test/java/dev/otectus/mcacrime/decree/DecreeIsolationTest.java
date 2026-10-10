package dev.otectus.mcacrime.decree;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A royal decree is not a crime (0.7.8), and this pins it at the source: nothing in the decree package
 * writes Heat or Karma, opens or revises a bounty warrant, files or resolves a case, touches MCA hearts,
 * or talks to MCA: Reputation. A decree's only consequences are its own record, the guard's challenge it
 * grounds, its fine and its custody term -- all of which go through code outside this package that
 * already answers for ordinary charges.
 */
class DecreeIsolationTest {

    private static final Path PACKAGE = Path.of("src", "main", "java", "dev", "otectus", "mcacrime", "decree");

    /** Calls that would make a decree change somebody's criminal standing. */
    private static final List<String> FORBIDDEN = List.of(
            "CrimeState.setHeat", "CrimeState.addHeat", "CrimeState.clearHeat", "CrimeState.setKarma",
            "CrimeState.addKarma", "applyIncident", "flushDeferredHeat",
            "putWarrant", "WarrantService", "BountyService", "BountyContractBoard",
            "addRecord(", "tryAddRecord", "replaceRecord", "CrimeCaseService", "CrimeIncidentMapping",
            "ReputationBridge", "CrimeIntegrationPump", "enqueue(", "addHearts", "rewardHearts",
            "setResistingArrest");

    @Test
    void theDecreePackageChangesNoCriminalStanding() throws IOException {
        List<String> offenders = new ArrayList<>();
        try (Stream<Path> files = Files.walk(PACKAGE)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                String source = Files.readString(file, StandardCharsets.UTF_8);
                for (String call : FORBIDDEN) {
                    if (source.contains(call)) {
                        offenders.add(PACKAGE.relativize(file) + " -> " + call);
                    }
                }
            }
        }
        assertEquals(List.of(), offenders, "a decree must not act like a crime record");
    }

    @Test
    void theOnlyCapitalsWriteIsTheChronicleLine() throws IOException {
        List<String> writes = new ArrayList<>();
        try (Stream<Path> files = Files.walk(PACKAGE)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                String source = Files.readString(file, StandardCharsets.UTF_8);
                int at = -1;
                while ((at = source.indexOf("CapitalsBridge.", at + 1)) >= 0) {
                    String call = source.substring(at + "CapitalsBridge.".length()).split("\\(", 2)[0];
                    if (!List.of("capitalAt", "anyCapitals", "standing", "court", "royalGuard", "playerWarrant",
                            "decreesReady", "has", "chronicle").contains(call.trim())) {
                        writes.add(PACKAGE.relativize(file) + " -> " + call);
                    }
                }
            }
        }
        assertTrue(writes.isEmpty(), "unexpected Capitals call(s) from the decree package: " + writes);
    }
}
