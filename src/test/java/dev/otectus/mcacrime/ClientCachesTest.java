package dev.otectus.mcacrime;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Every client cache is in the logout sweep.
 *
 * <p>A cache left out of it keeps the previous server's data — Heat, band colour, a countdown, a case
 * file — and shows it on the next server until something overwrites it, which for a player who is
 * clean there never happens. Nothing about the omission is visible in either file: the sweep still
 * compiles, still runs, and still clears the six caches somebody did remember.
 *
 * <p>Read from source rather than by loading the classes, because these are {@code Dist.CLIENT}
 * classes and this is a plain unit test.
 */
class ClientCachesTest {

    private static final Path CLIENT_ROOT = TestPaths.sources("dev", "otectus", "mcacrime", "client");
    private static final Path REGISTRY = CLIENT_ROOT.resolve("ClientCaches.java");

    /** {@code ClientSelfData::clear} in the registry's list. */
    private static final Pattern REGISTERED = Pattern.compile("(\\w+)::clear");

    @Test
    void everyCacheWithAClearIsRegistered() {
        Set<String> declared = cachesOnDisk();
        assertFalse(declared.isEmpty(), "no client cache declares a static clear(); the scan is wrong");
        assertEquals(declared, registered(),
                "ClientCaches.ALL and the client caches that declare a static clear() have drifted; a "
                        + "cache missing from ALL survives a disconnect and leaks into the next server");
    }

    /** The list is what the handler uses, so an unused entry would be a cache cleared twice. */
    @Test
    void theLogoutHandlerSweepsThroughTheRegistry() {
        String setup = read(CLIENT_ROOT.resolve("CrimeClientSetup.java"));
        assertEquals(1, count(setup, "ClientCaches.clearAll()"),
                "CrimeClientSetup must clear the caches through ClientCaches.clearAll()");
        assertEquals(0, count(setup, "Data.clear()"),
                "CrimeClientSetup must not name individual caches; that is what ClientCaches.ALL is for");
    }

    /** Every {@code client/*.java} whose body declares {@code public static void clear()}. */
    private static Set<String> cachesOnDisk() {
        try (Stream<Path> paths = Files.list(CLIENT_ROOT)) {
            Set<String> names = new TreeSet<>();
            List<Path> files = paths.filter(path -> path.getFileName().toString().endsWith(".java")).toList();
            for (Path file : files) {
                if (read(file).contains("public static void clear()")) {
                    String name = file.getFileName().toString();
                    names.add(name.substring(0, name.length() - ".java".length()));
                }
            }
            return names;
        } catch (IOException e) {
            throw new UncheckedIOException("Could not list " + CLIENT_ROOT.toAbsolutePath(), e);
        }
    }

    private static Set<String> registered() {
        Set<String> names = new TreeSet<>();
        Matcher matcher = REGISTERED.matcher(read(REGISTRY));
        while (matcher.find()) {
            names.add(matcher.group(1));
        }
        return names;
    }

    private static int count(String haystack, String needle) {
        int found = 0;
        for (int at = haystack.indexOf(needle); at >= 0; at = haystack.indexOf(needle, at + 1)) {
            found++;
        }
        return found;
    }

    private static String read(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read " + path.toAbsolutePath(), e);
        }
    }
}
