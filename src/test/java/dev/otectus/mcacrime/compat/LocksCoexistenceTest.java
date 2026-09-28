package dev.otectus.mcacrime.compat;

import dev.otectus.mcacrime.locks.ForeignLockPolicy;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Locks Reforged is a neighbour, not a dependency (M3.7, §3.16).
 *
 * <p>Three things have to hold at once. Native picking is the only default, so nothing about cuffs
 * routes through that mod any more. A target it already owns is refused rather than double-locked.
 * And with the mod absent, every one of those questions still answers, without loading a single one of
 * its classes.
 */
class LocksCoexistenceTest {

    /**
     * The source tree, resolved from {@code mcacrime.projectRoot}.
     *
     * <p>The NeoForge unit-test runner works out of {@code build/minecraft-junit}, so a relative
     * {@code src/...} path resolves to nothing. The {@code test} task supplies the property.
     */
    private static Path source(String... segments) {
        Path path = Path.of(System.getProperty("mcacrime.projectRoot", "."), "src", "main", "java",
                "dev", "otectus", "mcacrime");
        for (String segment : segments) {
            path = path.resolve(segment);
        }
        return path;
    }

    private static final Path BRIDGE = source("compat", "LocksReforgedBridge.java");
    private static final Path ADAPTER_DIR = source("compat", "locksreforged");

    private static String read(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    @Test
    void theDefaultIsToRefuseBeingTheSecondLock() {
        assertEquals(ForeignLockPolicy.REFUSE, ForeignLockPolicy.parse(null));
        assertEquals(ForeignLockPolicy.REFUSE, ForeignLockPolicy.parse("REFUSE"));
        assertEquals(ForeignLockPolicy.IGNORE, ForeignLockPolicy.parse(" ignore "));
        assertEquals(ForeignLockPolicy.REFUSE, ForeignLockPolicy.parse("both"),
                "an unrecognised policy fails to the safe side");
    }

    @Test
    void anAbsentModOwnsNothingAndIsNeverLoaded() {
        // ModList is not initialised in this suite, so installed() must answer without one, and
        // ownsLock must answer false without resolving anything of theirs.
        assertFalse(LocksReforgedBridge.installed());
        assertFalse(LocksReforgedBridge.ownsLock(null, null));
        assertFalse(LocksReforgedBridge.ownsLock(null,
                dev.otectus.mcacrime.locks.LockTarget.block(
                        net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("minecraft", "overworld"),
                        new net.minecraft.core.BlockPos(0, 64, 0))));
    }

    @Test
    void theAdapterIsOneClassAndNamesNoForeignTypeStatically() {
        List<String> classes;
        try (var files = Files.list(ADAPTER_DIR)) {
            classes = files.map(p -> p.getFileName().toString()).sorted().toList();
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
        assertEquals(List.of("LocksReforgedCompat.java"), classes,
                "the cuff lockpicking menu is gone; the fence provider and the ownership probe remain");

        String adapter = read(ADAPTER_DIR.resolve("LocksReforgedCompat.java"));
        assertFalse(adapter.contains("import melonslise"),
                "their types are reached by name at runtime, never imported");
        assertTrue(adapter.contains("Class.forName(\"melonslise.locks.common.util.LocksUtil\")"),
                "and the probe is their own lookup rather than a guess");
    }

    @Test
    void nothingRoutesCuffPickingThroughThatMod() {
        String bridge = read(BRIDGE);
        assertFalse(bridge.contains("openCuffs"), "the cuff menu path is gone");
        assertTrue(bridge.contains("locksReforgedFenceTrades"), "the fence provider stays");
        assertTrue(bridge.contains("status()"), "and so does the debug status");
    }

    @Test
    void theProbeDegradesLoudlyRatherThanSilently() {
        String adapter = read(ADAPTER_DIR.resolve("LocksReforgedCompat.java"));
        assertTrue(adapter.contains("reportProbeFailure"),
                "a probe that cannot resolve says so once");
        assertTrue(adapter.contains("ownershipProbeReported"), "and only once");
    }
}
