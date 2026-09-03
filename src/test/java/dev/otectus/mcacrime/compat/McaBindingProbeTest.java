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
import static org.junit.jupiter.api.Assertions.assertFalse;
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
 * the Minecraft types named in MCA's method signatures resolve to the very same classes
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
 * <p>Every build listed in {@code mca_probe_versions}, each opened in its own loader. Both 1.21.1
 * NeoForge builds ship the un-merged {@code net.conczin.mca} root, and the test pins that
 * expectation rather than accepting any root that happens to match: MCA has moved its packages on
 * both axes before, and probing only the dev-runtime build is how a root the binding does not
 * recognise reaches players.
 *
 * <p>Skipped rather than failed when no MCA jar has been resolved, so the suite still runs in a
 * checkout that has not fetched them.
 */
class McaBindingProbeTest {

    private static final String JARS_PROPERTY = "mcacrime.probe.jars";

    /** The root every probed 1.21.1 NeoForge MCA build ships, dotted and trailing-dotted as stored. */
    private static final String EXPECTED_ROOT = "net.conczin.mca.";

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
                assertEquals(EXPECTED_ROOT, resolution.root(),
                        jar.getFileName() + " bound at an unexpected package root. Every 1.21.1 NeoForge "
                                + "MCA build is un-merged; a different root here means MCA repackaged and "
                                + "CANDIDATE_ROOTS needs revisiting.");
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
     * §12.1's degradation case: <b>a member MCA no longer has must disable its own bridge and nothing
     * else</b>. A renamed manifest entry is the same shape as a removed member — the lookup finds no
     * method of that name — so the manifest is rebuilt with one optional entry pointed at a name no
     * MCA declares and resolved against a real jar.
     *
     * <p>The assertion that matters is the narrowness: the resolution is still {@code BOUND}, every
     * required member still resolves, every <em>other</em> optional member still resolves, and only
     * the sabotaged one reads as absent. That is what stops a future MCA removal from taking crime
     * detection down with a village label.
     */
    @Test
    void aMissingOptionalMemberDisablesOnlyItsOwnBridge() throws Exception {
        List<Path> jars = probeJars();
        Assumptions.assumeFalse(jars.isEmpty(),
                "No MCA jar to probe (" + JARS_PROPERTY + "); run via Gradle to exercise this.");

        McaBinding.Member sabotaged = McaBinding.VILLAGE_GET_NAME.renamed("getNameThatMcaDoesNotHave");
        List<McaBinding.Member> manifest = new ArrayList<>(McaBinding.MANIFEST);
        manifest.set(manifest.indexOf(McaBinding.VILLAGE_GET_NAME), sabotaged);

        try (ProbeClassLoader loader = new ProbeClassLoader(jars.get(0))) {
            McaBinding.Resolution resolution = McaBinding.resolveAgainst(loader, manifest);

            assertEquals(McaBinding.Status.BOUND, resolution.status(),
                    "an optional member going missing must not turn the whole binding PARTIAL");
            assertEquals(List.of(), resolution.unresolvedRequired(),
                    "sabotaging one optional member must not disturb any required one");
            assertEquals(List.of(sabotaged.toString()), resolution.unresolvedOptional(),
                    "exactly one bridge should report absent");
            assertFalse(resolution.has(sabotaged),
                    "the village-name bridge should read as absent, so McaHandles falls back to its label");

            // The neighbouring optional bridges -- guard population and the relationship graph -- are
            // the ones a naive "any miss disables MCA" implementation would take down with it.
            for (McaBinding.Member other : List.of(McaBinding.VILLAGE_GET_RESIDENTS,
                    McaBinding.VILLAGE_GET_POPULATION, McaBinding.VILLAGE_IS_VILLAGE,
                    McaBinding.VILLAGER_IS_GUARD, McaBinding.VILLAGER_SET_PROFESSION,
                    McaBinding.VILLAGE_MANAGER_GET, McaBinding.GET_FAMILY_ENTRY)) {
                assertTrue(resolution.has(other), other + " should be unaffected by an unrelated miss");
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
     * delegation would serve every probed version out of that single jar. Minecraft and JDK types
     * must still come from the parent, though, or MCA's method descriptors would name
     * different {@code Class} objects than the manifest's parameter hints and every overload
     * discrimination would miss.
     */
    private static final class ProbeClassLoader extends URLClassLoader {

        /** Mirrors {@code McaBinding.CANDIDATE_ROOTS}, which is private; keep the two in step. */
        private static final String[] MCA_ROOTS = {
                "net.conczin.mca.", "forge.net.conczin.mca.", "forge.net.mca.", "net.mca.",
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
