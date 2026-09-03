package dev.otectus.mcacrime;

import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Where the source tree is, for the tests that read it.
 *
 * <p>The NeoForge unit-test runner works out of {@code build/minecraft-junit}, not the project
 * directory, so a relative {@code src/main/resources/...} resolves to nothing and every resource
 * assertion turns into "file not found" — a failure that looks like missing data and is not. The
 * {@code test} task in {@code build.gradle} sets {@code mcacrime.projectRoot}; everything that
 * touches the tree resolves from it.
 *
 * @see NoMcaStaticLinkTest which does the same for the compiled-class output
 */
final class TestPaths {

    private TestPaths() {
    }

    /** The project directory, as supplied by the {@code test} task. */
    static Path projectRoot() {
        String root = System.getProperty("mcacrime.projectRoot");
        assertTrue(root != null && !root.isBlank(),
                "mcacrime.projectRoot is not set; the test task in build.gradle supplies it");
        return Paths.get(root);
    }

    /** {@code src/main/resources} plus the given segments. */
    static Path resources(String... segments) {
        return under(projectRoot().resolve(Paths.get("src", "main", "resources")), segments);
    }

    /** {@code src/main/java} plus the given segments. */
    static Path sources(String... segments) {
        return under(projectRoot().resolve(Paths.get("src", "main", "java")), segments);
    }

    private static Path under(Path base, String... segments) {
        Path path = base;
        for (String segment : segments) {
            path = path.resolve(segment);
        }
        return path;
    }
}
