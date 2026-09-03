package dev.otectus.mcacrime.compat;

import dev.otectus.mcacrime.compat.mca.McaBinding;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Resolves {@link McaBinding#MANIFEST} against the real MCA jar — the standing replacement for the
 * compile-time dependency this mod used to have.
 *
 * <h2>What this buys</h2>
 *
 * <p>Because no class names an MCA type any more (see {@code NoMcaStaticLinkTest}), the compiler can
 * no longer tell anyone when MCA renames or removes something the mod needs; a typo in a manifest
 * method name would otherwise surface as a silently dead feature rather than a build error. This test
 * restores that safety net: it walks the whole manifest against the MCA build on the dev runtime and
 * fails if anything required is missing, so a member MCA dropped shows up in CI instead of in a
 * player's crash report.
 *
 * <p>Each MCA build is opened in its own {@link ProbeClassLoader} rather than being read off the test
 * classpath, so the manifest is verified against real MCA without a single MCA class being linked into
 * the test JVM. The loader's parent is the test classloader, which is what makes the check meaningful:
 * Minecraft and Architectury types named in MCA's method signatures resolve to the very same classes
 * the manifest's parameter hints use, so a hint like {@code Village#getResidents(ServerLevel)}
 * genuinely discriminates between MCA's two same-arity overloads.
 *
 * <p>MCA packages themselves are loaded <em>child-first</em>, and that is not an optimisation. The
 * unit-test runtime carries the dev MCA build (the NeoForge mod loader refuses to boot without it),
 * so a parent-first loader would answer every {@code net.conczin.mca.*} request from that one jar and
 * every probed version would silently report the dev build's shape — exactly the single-version blind
 * spot this fleet exists to remove.
 *
 * <h2>Required vs optional</h2>
 *
 * <p>A miss in the <b>required</b> tier fails the build: the mod genuinely needs that member. A miss
 * in the <b>optional</b> tier is reported and allowed, because it is a member MCA removed and
 * {@code McaHandles} has a fallback for — no member is optional today, so any
 * miss is currently a build failure.
 *
 * <h2>Which MCA versions</h2>
 *
 * <p>Every build listed in {@code mca_probe_versions}, each opened in its own loader — one entry per
 * known package root. That fleet is the point: the root cannot be inferred from the version number
 * (7.7.0-beta.2 is {@code forge.net.mca}, 7.7.1-alpha.2 is {@code forge.net.conczin.mca}), so probing
 * only the dev-runtime build is how a root the binding does not recognise reaches players.
 *
 * <p>Skipped rather than failed when no MCA jar has been resolved, so the suite still runs in a
 * checkout that has not fetched them.
 */
class McaBindingProbeTest {

    private static final String JARS_PROPERTY = "mcacrime.probe.jars";

    @Test
    void manifestResolvesAgainstEveryProbedMcaJar() throws Exception {
        List<Path> jars = probeJars();
        Assumptions.assumeFalse(jars.isEmpty(),
                "No MCA jar to probe (" + JARS_PROPERTY + "); run via Gradle to exercise this.");

        // One loader per jar, never one loader spanning all of them. The package root is what this
        // really tests, and probeRoot() stops at the first root that resolves — so two MCA builds
        // sharing a loader would silently exercise whichever root won, and the other would go
        // unchecked. That is precisely how the missing forge.net.conczin.mca root shipped.
        for (Path jar : jars) {
            try (ProbeClassLoader loader = new ProbeClassLoader(jar)) {
                McaBinding.Resolution resolution = McaBinding.resolveAgainst(loader);

                assertNotNull(resolution.root(),
                        "No candidate package root matched " + jar.getFileName() + ". If MCA has moved "
                                + "again, add the new root to McaBinding's CANDIDATE_ROOTS.");
                assertEquals(List.of(), resolution.unresolvedRequired(),
                        jar.getFileName() + " is missing member(s) the mod requires. Either MCA renamed "
                                + "them (update the manifest in McaBinding) or removed them (declare the "
                                + "member with optionalVirtual and give McaHandles a fallback).");
                assertEquals(McaBinding.Status.BOUND, resolution.status(), jar.getFileName().toString());

                System.out.println("[probe] " + jar.getFileName() + " -> " + resolution.root()
                        + (resolution.unresolvedOptional().isEmpty() ? ""
                                : " (optional absent, fallbacks apply: " + resolution.unresolvedOptional() + ")"));
            }
        }
    }

    /**
     * Sanity check on the probe itself: with no MCA anywhere, resolution must report a clean absence
     * rather than throwing. This is the state the rest of the unit suite runs in, and the state a
     * server is in when MCA fails to load — it has to be boring, not fatal.
     */
    @Test
    void resolutionWithoutMcaIsAbsentAndDoesNotThrow() throws Exception {
        try (URLClassLoader empty = new URLClassLoader(new URL[0], null)) {
            McaBinding.Resolution resolution = McaBinding.resolveAgainst(empty);

            assertEquals(McaBinding.Status.ABSENT, resolution.status());
            assertEquals(null, resolution.root());
            assertTrue(resolution.unresolvedRequired().isEmpty(),
                    "An absent MCA is not a partial binding; nothing should be reported as a required miss.");
            // Every handle must still be a usable stub, because McaHandles hands these straight to
            // callers with no null check of their own.
            assertNotNull(resolution.handle(McaBinding.GET_VILLAGER_BRAIN));
            assertEquals(null, resolution.cls(McaBinding.VILLAGER_CLASS));
        }
    }

    /**
     * A {@link URLClassLoader} over one MCA jar that is child-first for MCA packages and parent-first
     * for everything else.
     *
     * <p>The split is the whole point. MCA is on the test runtime — the NeoForge unit-test loader
     * enforces the {@code mca} dependency in {@code neoforge.mods.toml} — so plain parent-first
     * delegation would serve every probed version out of that single jar. Minecraft, Architectury and
     * JDK types must still come from the parent, though, or MCA's method descriptors would name
     * different {@code Class} objects than the manifest's parameter hints and every overload
     * discrimination would miss.
     */
    private static final class ProbeClassLoader extends URLClassLoader {

        /** Mirrors {@code McaBinding.CANDIDATE_ROOTS}, which is private; keep the two in step. */
        private static final String[] MCA_ROOTS = {
                "forge.net.conczin.mca.", "forge.net.mca.", "net.conczin.mca.", "net.mca.",
        };

        ProbeClassLoader(Path jar) throws Exception {
            super(new URL[] {jar.toUri().toURL()}, McaBindingProbeTest.class.getClassLoader());
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            synchronized (getClassLoadingLock(name)) {
                if (!isMcaClass(name)) {
                    return super.loadClass(name, resolve);
                }
                Class<?> loaded = findLoadedClass(name);
                if (loaded == null) {
                    // This jar only, with no parent fallback: a root this jar does not carry must read
                    // as absent. Delegating upwards would hand back the dev MCA build on the test
                    // runtime and probeRoot() would report a root the probed version does not have.
                    loaded = findClass(name);
                }
                if (resolve) {
                    resolveClass(loaded);
                }
                return loaded;
            }
        }

        private static boolean isMcaClass(String name) {
            for (String root : MCA_ROOTS) {
                if (name.startsWith(root)) {
                    return true;
                }
            }
            return false;
        }
    }

    private static List<Path> probeJars() {
        List<Path> jars = new ArrayList<>();
        for (String entry : System.getProperty(JARS_PROPERTY, "").split(File.pathSeparator)) {
            if (!entry.isBlank()) {
                Path path = Paths.get(entry.trim());
                if (Files.isRegularFile(path)) {
                    jars.add(path);
                }
            }
        }
        return jars;
    }
}
