package dev.otectus.mcacrime;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Client code must never read a COMMON config value.
 *
 * <p>The mod registers a COMMON spec and a CLIENT spec and no SERVER spec, and Forge only
 * network-syncs a SERVER config. A COMMON value is therefore loaded from each side's <em>own</em>
 * file, which means a client reading one is reading whatever that player happened to put in their
 * config directory — not what the server is running. For a gameplay number that is a desync waiting to
 * be reported as a bug; for something like the escort leash radius it would be a player deciding their
 * own restraint rules.
 *
 * <p>The rule held by accident before 0.4.0 (a grep over the client package returned nothing). The
 * restraint visuals are the first client feature with server-owned numbers behind them, so it is
 * asserted here rather than remembered. The client is fed by packets; that is the seam.
 */
class ClientConfigIsolationTest {

    private static final List<Path> CLIENT_ROOTS = List.of(
            Path.of("src", "main", "java", "dev", "otectus", "mcacrime", "client"),
            Path.of("src", "main", "java", "dev", "otectus", "mcacrime", "mixin"));

    private static final String FORBIDDEN = "McaCrimeConfig.COMMON";

    @Test
    void noClientClassReadsAServerOwnedConfigValue() {
        List<String> offenders = new ArrayList<>();
        for (Path root : CLIENT_ROOTS) {
            for (Path source : sources(root)) {
                String body = read(source);
                if (body.contains(FORBIDDEN)) {
                    offenders.add(source.toString());
                }
            }
        }
        assertTrue(offenders.isEmpty(),
                "Client-side code read " + FORBIDDEN + " in " + offenders + ". COMMON is not synced, so "
                        + "that value is the player's own file, not the server's. Send it in a packet "
                        + "instead, or move the option to the CLIENT spec if it is genuinely a "
                        + "presentation preference.");
    }

    /** The guard must not pass vacuously: there has to be client code to check. */
    @Test
    void thereIsClientCodeToCheck() {
        int total = CLIENT_ROOTS.stream().mapToInt(root -> sources(root).size()).sum();
        assertTrue(total > 10, "expected the client package to exist and be non-trivial, found " + total);
    }

    /**
     * The 0.7.5 client packages are inside the walked roots (M2.10).
     *
     * <p>The roots above are walked recursively, so a new sub-package is covered the moment it exists
     * — which is exactly why it is worth asserting that these three <em>do</em> exist. A restraint HUD
     * that read a COMMON durability number, or a worn-model layer that read a COMMON toggle, would be
     * the player's own file deciding what they see of a server's rules; and a guard that silently
     * stopped covering the package would look identical to one that had nothing to find.
     */
    @Test
    void theRestraintClientPackagesAreCovered() {
        List<Path> covered = CLIENT_ROOTS.stream().flatMap(root -> sources(root).stream()).toList();
        for (String expected : List.of("render/restraint/RestraintSlotLayer.java",
                "hud/RestraintHudSection.java", "HoodOverlayHandler.java",
                "RestraintInputHandler.java")) {
            assertTrue(covered.stream().anyMatch(path -> path.toString().endsWith(
                            expected.replace('/', java.io.File.separatorChar))),
                    expected + " is not inside a walked client root, so nothing checks it");
        }
    }

    private static List<Path> sources(Path root) {
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        try (Stream<Path> paths = Files.walk(root)) {
            return paths.filter(path -> path.toString().endsWith(".java")).toList();
        } catch (IOException e) {
            throw new UncheckedIOException("Could not walk " + root.toAbsolutePath(), e);
        }
    }

    private static String read(Path source) {
        try {
            return Files.readString(source, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read " + source.toAbsolutePath(), e);
        }
    }
}
