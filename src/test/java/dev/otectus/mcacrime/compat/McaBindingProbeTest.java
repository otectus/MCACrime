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
 * <p>MCA is opened in its own {@link URLClassLoader} rather than placed on the test classpath, so the
 * manifest is verified against real MCA without a single MCA class being linked into the test JVM.
 * The loader's parent is the test classloader, which is what makes the check meaningful: Minecraft and
 * Architectury types named in MCA's method signatures resolve to the very same classes the manifest's
 * parameter hints use, so a hint like {@code Village#getResidents(ServerLevel)} genuinely
 * discriminates between MCA's two same-arity overloads.
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
            try (URLClassLoader loader = new URLClassLoader(new URL[] {jar.toUri().toURL()},
                    McaBindingProbeTest.class.getClassLoader())) {
                McaBinding.Resolution resolution = McaBinding.resolveAgainst(loader);

                assertNotNull(resolution.root(),
                        "No candidate package root matched " + jar.getFileName() + ". If MCA has moved "
                                + "again, add the new root to McaBinding's CANDIDATE_ROOTS.");
                assertEquals(List.of(), resolution.unresolvedRequired(),
                        jar.getFileName() + " is missing member(s) the mod requires. Either MCA renamed "
                                + "them (update the manifest in McaBinding) or removed them (declare the "
                                + "member with optionalVirtual and give McaHandles a fallback).");
                assertEquals(McaBinding.Status.BOUND, resolution.status(), jar.getFileName().toString());

                // The 0.7.2 thief-occupation bundle. Every member of it is declared *optional* in the
                // manifest so that a future MCA dropping one degrades this feature alone rather than
                // the whole mod -- which would make it invisible to the loop above. Requiring the
                // bundle here is what stops "optional" quietly becoming "absent on every build we
                // support", and it is the release gate spec §21.3 asks for.
                assertEquals(List.of(),
                        resolution.capabilityMissing(McaBinding.THIEF_OCCUPATION_CAPABILITY),
                        jar.getFileName() + " cannot support exclusive Thief occupations: the members "
                                + "listed are missing. A Thief would have to be suspended on this build.");

                // The vanilla supertype every occupational write goes through. MCA's villager extends
                // net.minecraft.world.entity.npc.Villager in all supported builds, which is why
                // OccupationCompat can reach villager data, trading XP, brain memories, offers and POI
                // release with one checked instanceof instead of reflecting SRG-named members.
                Class<?> mcaVillager = resolution.cls(McaBinding.VILLAGER_CLASS);
                assertNotNull(mcaVillager);
                assertTrue(net.minecraft.world.entity.npc.Villager.class.isAssignableFrom(mcaVillager),
                        jar.getFileName() + " no longer extends the vanilla Villager, so every "
                                + "vanilla-typed occupational call in OccupationCompat would be a "
                                + "silent no-op.");

                // setClothes is overloaded on VillagerLike, so the manifest's String hint is what picks
                // the one that can restore a captured outfit. Without the hint the winner would be
                // whichever getMethods() reported first -- a coin flip a passing probe could not see.
                assertTrue(clothingSetterTakesAString(resolution.cls(McaBinding.VILLAGER_LIKE_CLASS)),
                        jar.getFileName() + " has no VillagerLike#setClothes(String) for the rollback "
                                + "path to restore an outfit through.");

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

    /** True when the clothing setter the manifest hints at genuinely exists with a String parameter. */
    private static boolean clothingSetterTakesAString(Class<?> villagerLike) {
        if (villagerLike == null) {
            return false;
        }
        for (java.lang.reflect.Method method : villagerLike.getMethods()) {
            if (method.getName().equals("setClothes") && method.getParameterCount() == 1
                    && method.getParameterTypes()[0].equals(String.class)) {
                return true;
            }
        }
        return false;
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
