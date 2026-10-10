package dev.otectus.mcacrime.compat.capitals;

import dev.otectus.mcacrime.compat.CapitalView;
import dev.otectus.mcacrime.compat.CapitalsBridge;
import dev.otectus.mcacrime.compat.CapitalsCapability;
import dev.otectus.mcacrime.compat.CapitalsQueryResult;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Resolves {@link CapitalsBinding#MANIFEST} against a real MCA Capitals jar (0.7.8).
 *
 * <p>Run through {@code capitalsProbeTest -PcapitalsJar=...}, which pairs the jar with the MCA build
 * Capitals 1.3.x requires. Without a jar the binding tests skip; the absence test always runs.
 *
 * <p>Beyond "every member binds", it drives a real {@code CapitalRecord} through the erased handles: an
 * active player-ruled capital reads back with its id, village, state and player sovereign, and Capitals'
 * own authority resolver answers through the bound {@code resolve} -- so a binding that resolved to the
 * wrong overload, or a return value read the wrong way, fails here rather than in a ruler's decree.
 */
class CapitalsBindingProbeTest {

    private static List<Path> jars(String property) {
        List<Path> out = new ArrayList<>();
        for (String entry : System.getProperty(property, "").split(File.pathSeparator)) {
            if (!entry.isBlank() && Files.isRegularFile(Path.of(entry.trim()))) {
                out.add(Path.of(entry.trim()));
            }
        }
        return out;
    }

    private static URLClassLoader loader(boolean withMca) throws Exception {
        List<Path> capitals = jars("mcacrime.capitals.probe.jar");
        Assumptions.assumeFalse(capitals.isEmpty(), "No Capitals jar supplied; run capitalsProbeTest -PcapitalsJar=...");
        List<URL> urls = new ArrayList<>();
        for (Path jar : capitals) {
            urls.add(jar.toUri().toURL());
        }
        if (withMca) {
            List<Path> mca = jars("mcacrime.capitals.probe.mcaJars");
            assertTrue(!mca.isEmpty(), "capitalsProbeTest supplies no MCA jar; the resolver's descriptors need one");
            for (Path jar : mca) {
                urls.add(jar.toUri().toURL());
            }
        }
        return new URLClassLoader(urls.toArray(URL[]::new), CapitalsBindingProbeTest.class.getClassLoader());
    }

    @Test
    void theWholeManifestBindsAgainstTheSuppliedCapitals() throws Exception {
        try (URLClassLoader loader = loader(true)) {
            CapitalsBinding.Resolution resolution = CapitalsBinding.resolveAgainst(loader);
            assertEquals(List.of(), resolution.unresolved(), "Capitals member(s) did not bind");
            assertEquals(EnumSet.allOf(CapitalsCapability.class), resolution.capabilities());
            assertEquals(CapitalsBridge.State.FULL, resolution.state());
            System.out.println("[probe] Capitals bound: " + resolution.capabilities());
        }
    }

    @Test
    void aRealCapitalRecordReadsBackThroughTheErasedHandles() throws Exception {
        try (URLClassLoader loader = loader(true)) {
            CapitalsBinding.Resolution resolution = CapitalsBinding.resolveAgainst(loader);
            Class<?> recordType = Class.forName(CapitalsBinding.PACKAGE + "capital.CapitalRecord", true, loader);
            Class<?> stateType = Class.forName(CapitalsBinding.PACKAGE + "capital.CapitalState", true, loader);
            UUID capitalId = UUID.randomUUID();
            UUID ruler = UUID.randomUUID();
            Object record = recordType.getConstructor(UUID.class, Integer.class).newInstance(capitalId, 7);
            recordType.getMethod("setVillageDimensionId", String.class).invoke(record, "minecraft:overworld");
            recordType.getMethod("setState", stateType).invoke(record, stateConstant(stateType, "ACTIVE"));
            recordType.getMethod("setPlayerSovereign", boolean.class).invoke(record, true);
            recordType.getMethod("setPlayerSovereignId", UUID.class).invoke(record, ruler);

            ReflectiveCapitalsBridge bridge = new ReflectiveCapitalsBridge(resolution, "probe");
            CapitalsQueryResult<CapitalView> read = bridge.viewForTest(record);
            assertTrue(read.isAvailable(), "record did not read back: " + read.describe());
            CapitalView view = read.orThrow();
            assertEquals(capitalId, view.capitalId());
            assertEquals(7, view.villageId());
            assertEquals("minecraft:overworld", view.dimensionId());
            assertTrue(view.active(), "state read as " + view.state());
            assertTrue(view.ruledBy(ruler), "the player throne did not read back");

            // Capitals' own guard answers STRANGER for a missing level. The release jar is reobfuscated, so
            // building that answer calls Minecraft by its SRG name (Component.m_237115_), which this unit
            // classpath -- official names -- does not have. Either outcome proves the bound handle reached
            // the real resolver: STRANGER read by name, or a NoSuchMethodError on an SRG member from inside
            // it, which the bridge reports as Failed rather than throwing. The complete call is verified on
            // a production server by tools/capitals.
            CapitalsQueryResult<String> rank = bridge.rankForTest(record, ruler);
            boolean reachedResolver = rank.equals(CapitalsQueryResult.available("STRANGER"))
                    || rank instanceof CapitalsQueryResult.Failed<String> failed
                    && failed.reason().contains("NoSuchMethodError") && failed.reason().contains(".m_");
            assertTrue(reachedResolver, "the resolver handle did not reach Capitals: " + rank.describe());
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Object stateConstant(Class<?> type, String name) {
        return Enum.valueOf((Class) type, name);
    }

    /** The resolver's descriptors name MCA's Rank: without MCA, authority must read as unbound, not bound. */
    @Test
    void withoutMcaTheAuthorityCapabilityReadsUnbound() throws Exception {
        try (URLClassLoader loader = loader(false)) {
            CapitalsBinding.Resolution resolution = CapitalsBinding.resolveAgainst(loader);
            assertTrue(!resolution.has(CapitalsCapability.AUTHORITY),
                    "authority bound with no MCA on the loader; the probe would prove nothing about the pairing");
        }
    }

    /** No Capitals anywhere: a clean, silent absence, every handle still a stub. */
    @Test
    void resolutionWithoutCapitalsIsAbsentAndDoesNotThrow() throws Exception {
        try (URLClassLoader empty = new URLClassLoader(new URL[0], null)) {
            CapitalsBinding.Resolution resolution = CapitalsBinding.resolveAgainst(empty);
            assertEquals(CapitalsBridge.State.ABSENT, resolution.state());
            assertTrue(resolution.capabilities().isEmpty());
            assertTrue(resolution.unresolved().isEmpty());
            resolution.handle(CapitalsBinding.CAPITAL_BY_VILLAGE); // a stub, never null
        }
    }
}
