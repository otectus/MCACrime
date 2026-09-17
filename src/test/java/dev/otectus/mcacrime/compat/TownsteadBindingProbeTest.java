package dev.otectus.mcacrime.compat;

import dev.otectus.mcacrime.compat.townstead.TownsteadBinding;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.lang.reflect.Method;
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
 * Resolves {@link TownsteadBinding#MANIFEST} against a real Townstead jar.
 *
 * <h2>Why this exists</h2>
 *
 * <p>{@code NoTownsteadStaticLinkTest} guarantees that no class names a Townstead type, which means
 * the compiler cannot tell anyone when Townstead renames or removes something the manifest asks for:
 * a stale member name would surface as a silently dead capability rather than a build error. This is
 * the replacement safety net. It walks the whole manifest against the supplied jar and fails if
 * anything is missing, so a moved method shows up here instead of as a feature that quietly stopped.
 *
 * <h2>Why MCA has to be in the loader too</h2>
 *
 * <p>Townstead is compiled against MCA, so {@code TownsteadAPI} declares
 * {@code villager(VillagerEntityMCA)} beside the vanilla-descriptor {@code entity(Entity)} that is
 * actually bound. Enumerating a class's methods resolves every parameter type, so with MCA absent
 * {@code getMethods()} throws and the whole class reads as unbound. That is the correct production
 * behaviour — it is exactly how a mismatched Townstead/MCA pair degrades — but it would make this
 * probe vacuously green, so the Townstead jar is opened together with the MCA probe fleet
 * {@code McaBindingProbeTest} already resolves, and the assertions below would catch a loader that
 * lost it.
 *
 * <h2>Running it</h2>
 *
 * <pre>townsteadProbeTest -PtownsteadJar=/path/townstead-0.7.7+1.21.1.jar</pre>
 *
 * <p>One jar, because a NeoForge 1.21.1 Townstead is built against the single MCA package root this
 * branch ships ({@code net.conczin.mca}); the 1.20.1 baseline takes two because MCA's Forge-era
 * builds straddled a rename. Skipped rather than failed when no jar is supplied, so the ordinary
 * {@code test} run still passes on a checkout that has no Townstead jar anywhere — the same
 * arrangement {@code McaBindingProbeTest} uses.
 */
class TownsteadBindingProbeTest {

    /** Set by the {@code townsteadProbeTest} task from {@code -PtownsteadJar}. */
    private static final String TOWNSTEAD_JARS_PROPERTY = "mcacrime.townstead.probe.jars";

    /** The MCA fleet, shared with {@code McaBindingProbeTest} rather than declared a second time. */
    private static final String MCA_JARS_PROPERTY = "mcacrime.probe.jars";

    private static final String API_CLASS = "com.aetherianartificer.townstead.api.TownsteadAPI";

    @Test
    void theManifestResolvesAgainstTheSuppliedTownsteadJar() throws Exception {
        List<Path> townstead = jars(TOWNSTEAD_JARS_PROPERTY);
        Assumptions.assumeFalse(townstead.isEmpty(),
                "No Townstead jar supplied (" + TOWNSTEAD_JARS_PROPERTY + "); run "
                        + "`townsteadProbeTest -PtownsteadJar=<path>` to exercise this.");

        List<Path> mca = jars(MCA_JARS_PROPERTY);
        assertFalse(mca.isEmpty(),
                "The Townstead jar was supplied without an MCA build to read it against ("
                        + MCA_JARS_PROPERTY + " is empty). Enumerating TownsteadAPI's methods would then "
                        + "throw NoClassDefFoundError, every member would read as unbound, and this probe "
                        + "would pass while proving nothing.");

        List<Path> all = new ArrayList<>(townstead);
        all.addAll(mca);

        try (URLClassLoader loader = loaderFor(all)) {
            TownsteadBinding.Resolution resolution = TownsteadBinding.resolveAgainst(loader);

            assertEquals(List.of(), resolution.unresolved(),
                    "Townstead is missing member(s) the manifest asks for. Either Townstead renamed them "
                            + "(update TownsteadBinding's manifest) or removed them (drop the capability "
                            + "and give TownsteadHandles a fallback). Jars: " + all);
            assertEquals(TownsteadBridge.State.FULL, resolution.state(),
                    "Every declared capability must bind against a supported Townstead.");
            assertEquals(TownsteadBinding.DECLARED_CAPABILITIES, resolution.capabilities());
            assertNotNull(resolution.variant(),
                    "The MCA package root Townstead was built against could not be read. Diagnostics "
                            + "only, but if TownsteadAPI#villager has gone the variant probe needs a new "
                            + "method to read.");
            System.out.println("[probe] Townstead bound; MCA root = " + resolution.variant()
                    + ", capabilities = " + resolution.capabilities().size());
        }
    }

    /**
     * {@code TownsteadAPI#entity} is the one entry point whose parameter descriptor is vanilla-only,
     * and the entire read facade rests on that. If a future Townstead changed it to take an MCA type,
     * binding would still "work" and then fail at every call — so assert the shape, not just presence.
     */
    @Test
    void theEntryPointTakesAVanillaEntity() throws Exception {
        List<Path> townstead = jars(TOWNSTEAD_JARS_PROPERTY);
        Assumptions.assumeFalse(townstead.isEmpty(), "No Townstead jar supplied.");

        List<Path> all = new ArrayList<>(townstead);
        all.addAll(jars(MCA_JARS_PROPERTY));

        try (URLClassLoader loader = loaderFor(all)) {
            Class<?> api = Class.forName(API_CLASS, false, loader);
            Method entry = null;
            for (Method candidate : api.getMethods()) {
                if (candidate.getName().equals("entity") && candidate.getParameterCount() == 1) {
                    entry = candidate;
                    break;
                }
            }
            assertNotNull(entry, "TownsteadAPI#entity(Entity) is gone from the supplied jar; the read "
                    + "facade has no safe entry point left.");
            assertEquals("net.minecraft.world.entity.Entity", entry.getParameterTypes()[0].getName(),
                    "TownsteadAPI#entity no longer takes a vanilla Entity. Binding it would drag a "
                            + "relocated MCA type into every villager read.");
        }
    }

    /**
     * Sanity check on the probe itself: with no Townstead anywhere, resolution must report a clean
     * absence rather than throwing. That is the state the rest of the unit suite runs in, and the
     * state nearly every server is in — it has to be boring, not fatal. No assumption, so this runs in
     * the ordinary {@code test} pass too.
     */
    @Test
    void resolutionWithoutTownsteadIsAbsentAndDoesNotThrow() throws Exception {
        try (URLClassLoader empty = new URLClassLoader(new URL[0], null)) {
            TownsteadBinding.Resolution resolution = TownsteadBinding.resolveAgainst(empty);

            assertEquals(TownsteadBridge.State.ABSENT, resolution.state());
            assertTrue(resolution.capabilities().isEmpty());
            assertTrue(resolution.unresolved().isEmpty(),
                    "An absent Townstead is not a partial binding; nothing should be reported as a miss.");
            // Every handle must still be a usable stub: TownsteadHandles invokes them with no null check.
            assertNotNull(resolution.handle(TownsteadBinding.API_ENTITY));
        }
    }

    private static URLClassLoader loaderFor(List<Path> jars) throws Exception {
        List<URL> urls = new ArrayList<>();
        for (Path jar : jars) {
            urls.add(jar.toUri().toURL());
        }
        return new URLClassLoader(urls.toArray(URL[]::new),
                TownsteadBindingProbeTest.class.getClassLoader());
    }

    private static List<Path> jars(String property) {
        List<Path> jars = new ArrayList<>();
        for (String entry : System.getProperty(property, "").split(File.pathSeparator)) {
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
