package dev.otectus.mcacrime;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Standing tripwire for MCA Capitals (0.7.8): <b>no compiled class may reference a Capitals type, and
 * nothing outside {@code dev/otectus/mcacrime/compat/capitals/} may reference that package.</b>
 *
 * <p>{@link NoTownsteadStaticLinkTest}'s technique and reason. Capitals is compiled against MCA, so its
 * classes carry relocated MCA descriptors: one import would drag those into this mod's constant pool and
 * make it unloadable on a server without Capitals, which is nearly every server. {@code CapitalsBinding}
 * matches members by name and arity and keeps Capitals' package root as a <em>dotted</em> string, so a
 * slash-form reference found anywhere is a regression, with no exemption for the adapter itself.
 *
 * <p>The always-loadable seam types ({@code compat/CapitalsBridge}, {@code compat/CapitalView} ...) sit in
 * {@code compat} with a capital {@code C}, which differs from the guarded package's lowercase {@code c}
 * at the first byte after the separator, so they never match the package needle.
 */
class NoCapitalsStaticLinkTest {

    private static final String GUARDED_PACKAGE_PREFIX = "dev/otectus/mcacrime/compat/capitals/";

    /** A real Capitals class, method or field reference. Allowed nowhere. */
    private static final byte[] CAPITALS_REFERENCE_NEEDLE = "com/majesttyx/mcacapitals".getBytes(StandardCharsets.UTF_8);

    /** The manifest's string form. Allowed only inside the guarded package. */
    private static final byte[] CAPITALS_STRING_NEEDLE = "com.majesttyx.mcacapitals".getBytes(StandardCharsets.UTF_8);

    /** The guarded package itself, with its trailing slash. */
    private static final byte[] GUARDED_PACKAGE_NEEDLE = GUARDED_PACKAGE_PREFIX.getBytes(StandardCharsets.UTF_8);

    @Test
    void noCompiledClassReferencesACapitalsType() throws IOException {
        List<String> violations = scan(CAPITALS_REFERENCE_NEEDLE, false);
        assertTrue(violations.isEmpty(), "Class(es) statically reference com.majesttyx.mcacapitals. Every "
                + "Capitals access must resolve by name through CapitalsBinding. Offenders: " + violations);
    }

    @Test
    void onlyTheGuardedPackageNamesCapitalsInAString() throws IOException {
        List<String> violations = scan(CAPITALS_STRING_NEEDLE, true);
        assertTrue(violations.isEmpty(), "Class(es) outside " + GUARDED_PACKAGE_PREFIX + " carry Capitals' "
                + "package root as a string. Only CapitalsBinding's manifest may. Offenders: " + violations);
    }

    @Test
    void noAlwaysLoadedClassReferencesTheGuardedPackage() throws IOException {
        List<String> violations = scan(GUARDED_PACKAGE_NEEDLE, true);
        assertTrue(violations.isEmpty(), "Class(es) outside " + GUARDED_PACKAGE_PREFIX + " reference it "
                + "directly; the only entry point is CapitalsBridge's Class.forName on a dotted name. "
                + "Offenders: " + violations);
    }

    @Test
    void theAdapterNamesNoMcaPackageRoot() throws IOException {
        Path classesDir = classesDir();
        Path adapter = classesDir.resolve(GUARDED_PACKAGE_PREFIX);
        assertTrue(Files.isDirectory(adapter), "the Capitals adapter was not compiled");
        List<String> violations = new ArrayList<>();
        try (Stream<Path> paths = Files.walk(adapter)) {
            for (Path path : paths.filter(p -> p.toString().endsWith(".class")).toList()) {
                byte[] bytes = Files.readAllBytes(path);
                for (String root : new String[] {"forge/net/mca/", "forge/net/conczin/mca/", "net/conczin/mca/"}) {
                    if (containsNeedle(bytes, root.getBytes(StandardCharsets.UTF_8))) {
                        violations.add(classesDir.relativize(path) + " -> " + root);
                    }
                }
            }
        }
        assertTrue(violations.isEmpty(), "the Capitals adapter names an MCA type: " + violations);
    }

    /** From {@code mcacrime.projectRoot}: the unit-test runner works out of build/minecraft-junit. */
    private static Path classesDir() {
        Path classesDir = TestPaths.projectRoot().resolve(Paths.get("build", "classes", "java", "main"));
        assertTrue(Files.isDirectory(classesDir), classesDir + " does not exist; run compileJava first.");
        return classesDir;
    }

    private static List<String> scan(byte[] needle, boolean exemptGuardedPackage) throws IOException {
        Path classesDir = classesDir();
        List<String> violations = new ArrayList<>();
        try (Stream<Path> paths = Files.walk(classesDir)) {
            paths.filter(p -> p.toString().endsWith(".class")).forEach(p -> {
                String relative = classesDir.relativize(p).toString().replace('\\', '/');
                if (exemptGuardedPackage && relative.startsWith(GUARDED_PACKAGE_PREFIX)) {
                    return;
                }
                try {
                    if (containsNeedle(Files.readAllBytes(p), needle)) {
                        violations.add(relative);
                    }
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        }
        return violations;
    }

    private static boolean containsNeedle(byte[] haystack, byte[] needle) {
        outer:
        for (int i = 0; i <= haystack.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return true;
        }
        return false;
    }
}
