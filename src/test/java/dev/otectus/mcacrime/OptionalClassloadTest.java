package dev.otectus.mcacrime;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MCA: Crime must load and run with MCA: Reputation absent.
 *
 * <p>"Optional integration" is only true if the JVM starts cleanly without the optional jar, and the
 * cheapest strong check is that no compiled class outside the adapter so much as <em>names</em> a
 * companion type — if none does, no classloader can ever be asked for one. This is a bytecode
 * assertion rather than a runtime one precisely because the failure it guards against shows up on
 * somebody else's machine, halfway through a session, at whatever point first touched the chain.
 *
 * <p>The adapter package is excluded, and that exclusion is the whole point of the seam:
 * {@code compat.reputation} is <em>supposed</em> to name those types, and is reached only by name
 * after a {@code ModList} check. So the exclusion is verified to be exactly one directory, and the
 * class that stands between it and the rest of the mod is checked to be clean.
 */
class OptionalClassloadTest {

    private static final List<String> FORBIDDEN_PACKAGES = List.of(
            // Not an optional mod at all: the old loader. A surviving net.neoforged.neoforge reference in a
            // NeoForge 1.21.1 build is a port miss that no classloader will ever satisfy, and it fails
            // in exactly the same way an absent companion would, so it is checked in the same sweep.
            "net/minecraftforge/",
            "dev/otectus/mcareputation/",
            "dev/otectus/mcaquests/",
            "dev/otectus/mcaconversations/",
            "melonslise/locks/",
            "dev/architectury/",
            "me/shedaniel/");

    /** Client-only roots. Nothing outside them may be named by a class that runs on a server. */
    private static final List<String> CLIENT_PACKAGES = List.of(
            "dev/otectus/mcacrime/client/",
            "dev/otectus/mcacrime/mixin/");

    /** The only places allowed to name a companion type. Each is reached by name, from one bridge. */
    private static final String ADAPTER_PACKAGE = "dev/otectus/mcacrime/compat/reputation/";
    private static final String QUESTS_ADAPTER_PACKAGE = "dev/otectus/mcacrime/compat/mcaquests/";

    /**
     * Adapter packages nothing outside themselves may name, and the bridge that reaches each by string.
     *
     * <p>The classload seam is only as strong as its narrowest point: an adapter that names an optional
     * mod is fine, but a common class that names <em>the adapter</em> drags the whole chain in with it,
     * and the failure lands on somebody else's server rather than here. Locks Reforged is in the list
     * even though its adapter names no Locks type -- it looks its items up by registry id -- because
     * an adapter that later grew one would otherwise silently stop being isolated.
     */
    private static final List<String[]> ADAPTERS = List.of(
            new String[] {ADAPTER_PACKAGE,
                    "dev/otectus/mcacrime/compat/ReputationBridge.class",
                    "dev.otectus.mcacrime.compat.reputation.CrimeReputationCompat"},
            new String[] {QUESTS_ADAPTER_PACKAGE,
                    "dev/otectus/mcacrime/compat/McaQuestsBridge.class",
                    "dev.otectus.mcacrime.compat.mcaquests.McaQuestsBountyCompat"},
            new String[] {"dev/otectus/mcacrime/compat/locksreforged/",
                    "dev/otectus/mcacrime/compat/LocksReforgedBridge.class",
                    "dev.otectus.mcacrime.compat.locksreforged.LocksReforgedCompat"});

    /**
     * Every MCA package root, so this guard cannot pass vacuously. It used to test only
     * {@code import forge.net.mca.} — which meant that when MCA renamed its base package to
     * {@code net.conczin.mca}, a file could have imported the new root and this test would still have
     * been green. A root list, not a single string, is the fix.
     */
    private static final List<String> MCA_IMPORT_PREFIXES = List.of(
            "import forge.net.conczin.mca.",
            "import forge.net.mca.",
            "import net.conczin.mca.",
            "import net.mca.");

    /**
     * The class output on disk, resolved from {@code mcacrime.projectRoot}. Deliberately not from a
     * {@code getResource} URL: that path is percent-encoded, so any space in the checkout path (this
     * one has one) turns into a {@code NoSuchFileException}. The unit-test runner also works out of
     * build/minecraft-junit, so a relative path is no good either.
     */
    private static Path compiledClasses() {
        String projectRoot = System.getProperty("mcacrime.projectRoot");
        assertNotNull(projectRoot, "mcacrime.projectRoot is not set; the test task in build.gradle supplies it");
        Path classes = Paths.get(projectRoot, "build", "classes", "java", "main");
        assertTrue(Files.isDirectory(classes), "compiled classes not found at " + classes);
        return classes;
    }

    private static String normalise(Path root, Path file) {
        return root.relativize(file).toString().replace('\\', '/');
    }

    @Test
    void noClassOutsideTheAdapterNamesACompanionMod() throws IOException {
        Path root = compiledClasses();
        List<String> offenders = new ArrayList<>();
        try (Stream<Path> files = Files.walk(root)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".class")).toList()) {
                String relative = normalise(root, file);
                if (relative.startsWith(ADAPTER_PACKAGE) || relative.startsWith(QUESTS_ADAPTER_PACKAGE)
                        || relative.startsWith("dev/otectus/mcacrime/compat/locksreforged/")) {
                    continue;
                }
                // The constant pool stores type names in internal form, so a plain byte-level scan
                // finds every reference — field types, signatures, annotations and all — without
                // needing a bytecode library.
                String bytes = new String(Files.readAllBytes(file), StandardCharsets.ISO_8859_1);
                for (String forbidden : FORBIDDEN_PACKAGES) {
                    if (bytes.contains(forbidden)) {
                        offenders.add(relative + " -> " + forbidden);
                    }
                }
            }
        }
        assertTrue(offenders.isEmpty(),
                "these classes name an optional mod and would fail to load without it: " + offenders);
    }

    /**
     * The bridge is what every other class reaches the integration through, so it above all must stay
     * clean: a companion type in <em>its</em> constant pool would defeat the seam entirely, since the
     * bridge is loaded unconditionally at startup.
     */
    @Test
    void theBridgeItselfNamesNoCompanionType() throws IOException {
        Path root = compiledClasses();
        Path bridge = root.resolve("dev/otectus/mcacrime/compat/ReputationBridge.class");
        assertTrue(Files.exists(bridge), "ReputationBridge.class not found");

        String bytes = new String(Files.readAllBytes(bridge), StandardCharsets.ISO_8859_1);
        for (String forbidden : FORBIDDEN_PACKAGES) {
            assertTrue(!bytes.contains(forbidden),
                    "ReputationBridge names " + forbidden + "; it must reach the adapter by name only");
        }
        assertTrue(bytes.contains("dev.otectus.mcacrime.compat.reputation.CrimeReputationCompat"),
                "the bridge should reach the adapter through a string, not a type reference");
    }

    /**
     * The exclusion above must not quietly widen. If somebody adds a second class to the adapter
     * package that has nothing to do with the companion, it silently stops being checked.
     */
    @Test
    void onlyTheAdapterLivesInTheExcludedPackage() throws IOException {
        Path root = compiledClasses();
        Path adapterDir = root.resolve(ADAPTER_PACKAGE);
        if (!Files.exists(adapterDir)) {
            return; // built without the sibling; the package legitimately does not exist
        }
        try (Stream<Path> files = Files.walk(adapterDir)) {
            List<String> unexpected = files
                    .filter(path -> path.toString().endsWith(".class"))
                    .map(path -> normalise(root, path))
                    .filter(name -> !name.contains("CrimeReputationCompat"))
                    .toList();
            assertTrue(unexpected.isEmpty(),
                    "the classload exclusion covers only CrimeReputationCompat and its nested classes; "
                            + "these would escape the check: " + unexpected);
        }
    }

    /**
     * Common code must not reach into client code, and the restraint resolver is the file where that
     * would happen first.
     *
     * <p>{@code RestraintVisualResolver} decides how a restrained subject looks, which sounds like a
     * rendering concern and is not: it runs on the server and feeds the packets. If it ever named a
     * class under {@code client/} or {@code mixin/}, a dedicated server would classload rendering code
     * the moment somebody was arrested — and the crash would land on a host, not on the author's
     * machine. Byte-scanned rather than import-scanned for the same reason as the MCA guard below: the
     * constant pool catches a field type or a signature that no import line mentions.
     */
    @Test
    void theRestraintResolverNamesNoClientClass() throws IOException {
        Path root = compiledClasses();
        Path resolver = root.resolve("dev/otectus/mcacrime/enforcement/RestraintVisualResolver.class");
        assertTrue(Files.exists(resolver), "RestraintVisualResolver.class not found");

        String bytes = new String(Files.readAllBytes(resolver), StandardCharsets.ISO_8859_1);
        for (String clientPackage : CLIENT_PACKAGES) {
            assertTrue(!bytes.contains(clientPackage),
                    "RestraintVisualResolver names " + clientPackage + "; it runs server-side and must "
                            + "stay loadable on a dedicated server");
        }
    }

    /**
     * The same rule for the shared restraint value types the packets carry. They sit in
     * {@code enforcement} precisely so a dedicated server can name them; a client reference in either
     * would undo that and take the packets down with it.
     */
    @Test
    void theRestraintValueTypesNameNoClientClass() throws IOException {
        Path root = compiledClasses();
        List<String> offenders = new ArrayList<>();
        for (String name : List.of("RestraintVisualState", "RestraintVisualType", "RestraintSync")) {
            Path file = root.resolve("dev/otectus/mcacrime/enforcement/" + name + ".class");
            assertTrue(Files.exists(file), name + ".class not found");
            String bytes = new String(Files.readAllBytes(file), StandardCharsets.ISO_8859_1);
            for (String clientPackage : CLIENT_PACKAGES) {
                if (bytes.contains(clientPackage)) {
                    offenders.add(name + " -> " + clientPackage);
                }
            }
        }
        assertTrue(offenders.isEmpty(),
                "these common classes name client-only code: " + offenders);
    }

    /**
     * The other invariant this mod documents but never enforced: MCA itself is reached only through
     * the compat layer, so a drift in MCA's shape breaks one file rather than twenty.
     *
     * <p>Since the move to a runtime binding this is a backstop rather than the primary guard —
     * {@code NoMcaStaticLinkTest} byte-scans the compiled output and now forbids an MCA reference
     * <em>anywhere</em>, including inside {@code compat} itself. This test keeps the cheaper,
     * source-level signal, and catches the mistake earlier in a diff.
     */
    @Test
    void onlyTheCompatLayerNamesMcaTypes() throws IOException {
        Path source = Paths.get(System.getProperty("mcacrime.projectRoot", ""),
                "src", "main", "java", "dev", "otectus", "mcacrime");
        assertTrue(Files.isDirectory(source), "source tree not found at " + source.toAbsolutePath());

        List<String> offenders = new ArrayList<>();
        try (Stream<Path> files = Files.walk(source)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                String relative = file.toString().replace('\\', '/');
                if (relative.contains("/compat/")) {
                    continue;
                }
                String text = Files.readString(file, StandardCharsets.UTF_8);
                if (MCA_IMPORT_PREFIXES.stream().anyMatch(text::contains)) {
                    offenders.add(relative);
                }
            }
        }
        assertTrue(offenders.isEmpty(),
                "MCA types must stay behind McaCompat; these files import them directly: " + offenders);
    }

    /**
     * No class outside an adapter package may name that package, and each bridge must reach its
     * adapter through a string.
     *
     * <p>This is the invariant {@link #theBridgeItselfNamesNoCompanionType()} checks for Reputation,
     * generalised to every optional mod: the bridges load unconditionally at startup, so a type
     * reference in one of them -- or in anything else that runs on a vanilla-plus-MCA server -- would
     * defeat the seam completely.
     */
    @Test
    void everyAdapterIsReachedOnlyByName() throws IOException {
        Path root = compiledClasses();
        List<String> offenders = new ArrayList<>();
        for (String[] adapter : ADAPTERS) {
            String adapterPackage = adapter[0];
            Path bridge = root.resolve(adapter[1]);
            assertTrue(Files.exists(bridge), adapter[1] + " not found");
            String bridgeBytes = new String(Files.readAllBytes(bridge), StandardCharsets.ISO_8859_1);
            assertTrue(bridgeBytes.contains(adapter[2]),
                    adapter[1] + " should reach its adapter through a string, not a type reference");

            try (Stream<Path> files = Files.walk(root)) {
                for (Path file : files.filter(path -> path.toString().endsWith(".class")).toList()) {
                    String relative = normalise(root, file);
                    if (relative.startsWith(adapterPackage)) {
                        continue;
                    }
                    String bytes = new String(Files.readAllBytes(file), StandardCharsets.ISO_8859_1);
                    if (bytes.contains(adapterPackage)) {
                        offenders.add(relative + " -> " + adapterPackage);
                    }
                }
            }
        }
        assertTrue(offenders.isEmpty(),
                "these classes name an optional adapter package and would drag it into a load: " + offenders);
    }
}
